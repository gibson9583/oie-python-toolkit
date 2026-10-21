#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Reject a concurrent template save through stock deployment, then retry stably."""
import argparse
from pathlib import Path
import shutil
import socket
import concurrent.futures
import copy
import json
import time
import uuid
import xml.etree.ElementTree as ET
from live_engine_helpers import Api, javascript_step, require_listener, write
from run_code_template_isolated import ROOT, make_template, make_channel, run as run_isolated


def run(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / 'template-race-scenario.py')
    template_id, library_id, channel_id = [str(uuid.uuid4()) for _ in range(3)]
    template = make_template(template_id, "result = arguments[0] + '|A'\n")
    write(evidence / 'template-A-submitted.json', template)
    saved = api.call('/codeTemplates/' + template_id, 'PUT', template)
    assert saved is True or saved.get('boolean') is True, saved
    library = {'list': {'codeTemplateLibrary': [{'@version': '4.6.0', 'id': library_id,
        'name': 'Template deploy race review', 'revision': 0, 'description': 'Owned review case',
        'includeNewChannels': False, 'enabledChannelIds': {'string': channel_id},
        'disabledChannelIds': '', 'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}}
    linked = api.call('/codeTemplateLibraries', 'PUT', library)
    assert linked is True or linked.get('boolean') is True, linked
    ready = evidence / 'deploy-hook-ready'
    release = evidence / 'deploy-hook-release'
    channel = ET.fromstring(make_channel(channel_id, ingress))
    for path in ['sourceConnector/transformer/elements', 'destinationConnectors/connector/transformer/elements',
            'destinationConnectors/connector/responseTransformer/elements']:
        channel.find(path).clear()
    script = ("try { msg = python_function(String(msg)); } "
        "catch (failure) { msg = 'REJECTED|' + String(failure.message); }\n")
    channel.find('sourceConnector/transformer/elements').append(javascript_step(script, 'Template race'))
    deploy_script = (
        "var __reviewReady = new java.io.File(" + json.dumps(str(ready)) + ");\n"
        "var __reviewRelease = new java.io.File(" + json.dumps(str(release)) + ");\n"
        "__reviewReady.createNewFile();\n"
        "var __reviewDeadline = java.lang.System.currentTimeMillis() + 45000;\n"
        "while (!__reviewRelease.exists()) {\n"
        " if (java.lang.System.currentTimeMillis() > __reviewDeadline) throw new Error('Owned review gate timed out');\n"
        " java.lang.Thread.sleep(25);\n"
        "}\n")
    channel.find('deployScript').text = deploy_script
    xml = ET.tostring(channel, encoding='unicode')
    (evidence / 'race-channel.xml').write_text(xml)
    saved = api.call('/channels/' + channel_id, 'PUT', xml, 'application/xml')
    assert saved is True or saved.get('boolean') is True, saved
    timeline = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
        deploying = executor.submit(api.call, '/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST', status=(500,))
        try:
            deadline = time.monotonic() + 30
            while not ready.exists():
                if deploying.done():
                    raise AssertionError(('Deployment returned before hook', deploying.result()))
                if time.monotonic() > deadline:
                    raise TimeoutError('Owned deploy script did not signal')
                time.sleep(0.05)
            timeline.append({'event': 'stock-deploy-script-running', 'time': time.time()})
            updated = copy.deepcopy(template)
            updated['codeTemplate']['properties']['pythonScript'] = "result = arguments[0] + '|B'\n"
            save_b = api.call('/codeTemplates/' + template_id + '?override=true', 'PUT', updated)
            write(evidence / 'template-B-save-during-deploy.json', save_b)
            assert save_b is True or save_b.get('boolean') is True, save_b
            timeline.append({'event': 'ordinary-template-save-completed-during-deploy', 'time': time.time()})
        finally:
            release.write_text('Release owned deploy hook\n')
        deployed = deploying.result(timeout=60)
        timeline.append({'event': 'deployment-returned', 'time': time.time(), 'result': deployed})
    write(evidence / 'race-deploy-result.json', deployed)
    write(evidence / 'race-timeline.json', timeline)
    assert 'Python functions changed while channel transformers were being compiled' in json.dumps(deployed), deployed
    # The rejection happens before publication/start, so the owned listener is absent.
    with socket.socket() as probe:
        probe.settimeout(2)
        assert probe.connect_ex(('127.0.0.1', ingress)) != 0, 'Rejected channel published its listener'
    write(evidence / 'race-status-after-rejection.json', api.call('/channels/' + channel_id + '/status', status=(404,)))
    transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
    write(evidence / 'race-redeploy-result.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
    recovered = transport.call('/python/', 'POST', 'hello', 'text/plain')
    write(evidence / 'race-message-after-redeploy.json', recovered)
    assert recovered == 'hello|B', recovered
    write(evidence / 'messages-final.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    write(evidence / 'template-final.json', api.call('/codeTemplates/' + template_id))
    write(evidence / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    receipt.update(status='passed', channelId=channel_id, templateId=template_id, libraryId=library_id,
        assertions=['ordinary-template-save-completes-during-deploy', 'snapshot-mismatch-rejected-before-publication',
            'stable-retry-binds-current-template'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run_isolated(parser.parse_args(), run)
