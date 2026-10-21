#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify editor tooling against an owned engine; optionally use real host Monaco.

Uses the maintained isolated runner's ownership, logs, database observations and
cleanup. Set OIE_WEB_HOST to the built host repository for browser qualification.
"""
import argparse
import ast
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
from release_contract import default_plugin
import shutil
import subprocess
import threading
import uuid

from live_engine_helpers import write
from run_code_template_isolated import ROOT, PYTHON, make_channel, run


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / 'editor-services-runner.py')
    info = api.call(PYTHON + '/runtime')
    catalog = api.call(PYTHON + '/profiles')
    channel_id = str(uuid.uuid4())
    saved = api.call('/channels/' + channel_id, 'PUT', make_channel(channel_id, ingress), 'application/xml')
    assert saved is True or saved.get('boolean') is True, saved
    write(evidence / 'editor-channel.json', api.call('/channels/' + channel_id))
    base = dict(role='writer', channelId=channel_id, environmentProfileId=profile['id'],
                expectedRegistryEpoch=catalog['registryEpoch'], expectedEnvironmentRevision=profile['revision'],
                expectedNodeId=info['nodeId'])
    observations = []

    def call(label, source='logger.', operation='complete', expected=(200,), **changes):
        request = dict(base, pythonScript=source, operation=operation, line=1, column=len(source))
        request.update(changes)
        response = api.call(PYTHON + '/editor', 'POST', request, status=expected)
        observations.append(dict(label=label, request=request, response=response))
        write(evidence / 'editor-endpoint-results.json', observations)
        return response

    malformed = api.call(PYTHON + '/editor', 'POST', '{', status=(400,))
    observations.append(dict(label='malformed JSON', response=malformed))
    call('out-of-bounds position', line=99, expected=(400,))
    call('unsupported operation', operation='execute', expected=(400,))
    call('wrong node', expectedNodeId='wrong-node', expected=(409,))
    call('stale environment revision', expectedEnvironmentRevision=profile['revision'] + 1, expected=(409,))
    invalid = call('invalid source stays unchanged', source='return {', operation='format')
    assert invalid['ok'] is False, invalid
    source = '# preserve comment\ntext="a=b,  c"\nreturn {"value":1,"list":[1,2]}\n'
    formatted = call('document formatting preserves AST', source=source, operation='format')
    assert formatted['ok'] and formatted['text'] != source, formatted
    assert ast.dump(ast.parse(source), include_attributes=False) == ast.dump(ast.parse(formatted['text']), include_attributes=False)
    selected = call('selection formatting', source='left=1\nreturn {"value":1}\nright=2\n', operation='format',
                    selection={'startLineNumber': 2, 'endLineNumber': 2})
    assert selected['ok'] and 'return ' in selected['text'] and 'left' not in selected['text'] and 'right' not in selected['text'], selected
    for role, name, fields in [('reader', 'Message', ('payload', 'source_map', 'destination_ids')),
                               ('writer', 'Response', ('status', 'data', 'status_message', 'error'))]:
        source = 'return ' + name + '('
        signature = call('automatic ' + name + ' signature', source=source, role=role, operation='signature')
        assert signature['ok'] and signature['signatures'], signature
        assert all(field in signature['signatures'][0]['label'] for field in fields), signature
    for role, source in [('writer', 'return Status.'), ('response-transformer', 'Status.')]:
        statuses = call('automatic Status in ' + role, source=source, role=role)
        assert statuses['ok'] and {'SENT', 'ERROR', 'QUEUED'} <= {item['name'] for item in statuses['items']}, statuses
    marker = evidence / 'authored-source-executed'
    assert not marker.exists()
    authored = 'open(' + repr(str(marker)) + ', "w").write("unexpected")\nlogger.'
    analyzed = call('authored source is not executed', source=authored, line=2, column=7)
    assert analyzed['ok'] and not marker.exists(), analyzed
    sources = [item for item in info['packageSources'] if item['kind'] != 'builtin']
    assert sources, 'Unprepared-environment check requires configured package source'
    custom = api.call(PYTHON + '/profiles', 'POST', dict(requestId=str(uuid.uuid4()), expectedRegistryEpoch=catalog['registryEpoch'],
        name='Editor unprepared qualification', runtimeId=info['runtimes'][0]['id'], packageSourceId=sources[0]['id'], requirementsText=''))['profile']
    call('unprepared environment', environmentProfileId=custom['id'], expectedEnvironmentRevision=custom['revision'], expected=(503,))

    if not os.environ.get('OIE_WEB_HOST'):
        write(evidence / 'browser-skipped.json', {'reason': 'OIE_WEB_HOST is not set; endpoint checks only'})
        receipt.update(status='passed', editorEndpointsQualified=True, editorBrowserQualified=False, channelId=channel_id)
        return

    # Only these two loopback routes are exposed. Credentials stay inside the
    # existing Api cookie jar; every forwarded call rechecks the live PID/listener.
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def respond(self, code, value):
            raw = json.dumps(value).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path != '/catalog':
                return self.respond(404, {})
            self.respond(200, dict(runtime=api.call(PYTHON + '/runtime'), profiles=api.call(PYTHON + '/profiles'),
                                   channelId=channel_id, profileId=profile['id']))

        def do_POST(self):
            if self.path != '/editor':
                return self.respond(404, {})
            length = int(self.headers.get('Content-Length', '0'))
            if length < 1 or length > 2 * 1024 * 1024:
                return self.respond(400, {'message': 'Invalid proxy request length'})
            try:
                body = json.loads(self.rfile.read(length))
                value = api.call(PYTHON + '/editor', 'POST', body)
                self.respond(200, {'string': json.dumps(value)})
            except Exception as error:
                self.respond(502, {'message': str(error)})

    proxy = HTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    try:
        env = dict(os.environ, OIE_EDITOR_PROXY='http://127.0.0.1:' + str(proxy.server_port), OIE_WEB_ART=str(evidence / 'browser'))
        script = ROOT / 'package/webadmin/test/browser-python-services.mjs'
        shutil.copyfile(script, evidence / script.name)
        with (evidence / 'browser-stdout.log').open('w') as output:
            result = subprocess.run(['node', str(script)], cwd=ROOT / 'package/webadmin', env=env,
                                    stdout=output, stderr=subprocess.STDOUT, timeout=240)
        assert result.returncode == 0, 'Browser editor-services qualification failed; see browser-stdout.log'
    finally:
        proxy.shutdown()
        thread.join(timeout=5)
        proxy.server_close()
        assert not thread.is_alive(), 'Editor proxy did not stop'
    receipt.update(status='passed', editorEndpointsQualified=True, editorBrowserQualified=True, channelId=channel_id)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
