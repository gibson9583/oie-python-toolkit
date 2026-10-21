#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify authored returns on a private engine with retained evidence/cleanup."""
import argparse
from pathlib import Path
from release_contract import default_plugin
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import Api, channel_xml, javascript_step, require_listener, step, write
from run_code_template_isolated import ROOT, make_template, run
from run_filter_isolated import rule
from run_live_engine_integration import connector_messages
from run_reader_message_isolated import maximum


def sample_cases(api, profile, evidence):
    runtime = api.call('/extensions/python-runtime/runtime')
    selected = api.call('/extensions/python-runtime/profiles/' + profile['id'])
    base = dict(environmentProfileId=profile['id'], expectedRegistryEpoch=selected['registryEpoch'],
                expectedEnvironmentRevision=selected['revision'], expectedNodeId=runtime['nodeId'],
                contractVersion=1, timeoutMillis=30000)
    cases = [
        ('filter-finally', 'source-filter', "try:\n    msg += '|returned'\n    return True\nfinally:\n    channelMap['final'] = True\n", 'success'),
        ('filter-false', 'destination-filter', 'return False', 'success'),
        ('filter-invalid', 'source-filter', "channelMap['partial'] = True\nreturn 1", 'error'),
        ('filter-assignment-is-not-return', 'source-filter', 'result = False', 'success'),
        ('filter-local-result', 'source-filter', "assert 'result' not in globals()\nresult = True\nreturn result", 'success'),
        ('filter-empty', 'source-filter', '', 'success'),
        ('filter-fallthrough-finally', 'destination-filter', "try:\n    msg += '|fallthrough'\nfinally:\n    channelMap['final'] = True", 'success'),
        ('filter-explicit-none', 'source-filter', 'return None', 'error'),
        ('filter-bare-return', 'destination-filter', 'return', 'error'),
        ('reader-one', 'reader', "return Message('one', source_map={'value': 42})", 'success'),
        ('reader-explicit-import', 'reader', "from oie_python import Message\nreturn Message('compatible')", 'success'),
        ('reader-many', 'reader', "return [Message('one'), Message('two')]", 'success'),
        ('reader-string', 'reader', "return 'one'", 'success'),
        ('reader-string-list', 'reader', "return ['one', '', 'two']", 'success'),
        ('reader-mixed-list', 'reader', "return ['one', Message('two', source_map={'origin': 'override'})]", 'success'),
        ('reader-empty-string', 'reader', "return ''", 'success'),
        ('reader-none', 'reader', "result = 'ignored'\nreturn None", 'success'),
        ('reader-assignment-is-not-return', 'reader', "assert 'result' not in globals()\nresult = Message('not-emitted')", 'success'),
        ('reader-invalid', 'reader', "return ['one', None]", 'error'),
        ('reader-source-defaults', 'reader', "assert 'poll' not in globals()\nassert sourceMap['pollId'] == 'sample'\nassert sourceMap['origin'] == 'seeded'\nsourceMap.put('shared', {'count': 2})\nreturn [Message('one'), Message('two', source_map={'origin': 'override'})]", 'success'),
        ('reader-invalid-source-none', 'reader', "sourceMap['invalid'] = object()\nglobalMap['mustNotCommit'] = True\nreturn None", 'error'),
        ('reader-source-rebinding', 'reader', "sourceMap = {'notExported': True}\nreturn Message('one')", 'success'),
        ('reader-source-cleared', 'reader', "sourceMap.clear()\nreturn Message('one')", 'success'),
        ('writer-response', 'writer', "return Response(Status.SENT, 'ack')", 'success'),
        ('writer-explicit-import', 'writer', "from oie_python import Response, Status\nreturn Response(Status.SENT, 'compatible')", 'success'),
        ('writer-status', 'writer', "return Status.ERROR", 'success'),
        ('writer-string', 'writer', "return 'ERROR'", 'success'),
        ('writer-none', 'writer', "result = 'ignored'\nreturn None", 'success'),
        ('writer-assignment-is-not-return', 'writer', "assert 'result' not in globals()\nresult = Response(Status.ERROR, 'not-returned')", 'success'),
    ]
    observations = []
    for name, role, source, status in cases:
        request = dict(base, requestId=str(uuid.uuid4()), role=role, pythonScript=source)
        if 'filter' in role:
            request['sampleContext'] = {'msg': {'kind': 'raw', 'text': 'sample'}}
        elif role == 'reader':
            request['sampleContext'] = {'sourceMap': {'origin': 'seeded'}}
        response = api.call('/extensions/python-runtime/scripts/test', 'POST', request)
        observations.append(dict(name=name, request=request, response=response))
        write(evidence / 'sample-results.json', observations)
        assert response['status'] == status, response
        if name == 'filter-finally':
            assert response['output']['accepted'] is True, response
            assert response['output']['mapChanges']['channelMap']['set']['final'] is True, response
        elif name == 'filter-false':
            assert response['output']['accepted'] is False, response
        elif name in ('filter-assignment-is-not-return', 'filter-local-result', 'filter-empty', 'filter-fallthrough-finally'):
            assert response['output']['accepted'] is True, response
            if name == 'filter-fallthrough-finally':
                assert response['output']['mapChanges']['channelMap']['set']['final'] is True, response
                assert response['output']['msg']['text'] == 'sample|fallthrough', response
        elif name in ('reader-none', 'reader-assignment-is-not-return', 'reader-empty-string'):
            assert response['output']['messages'] == [], response
        elif name in ('reader-string', 'reader-string-list', 'reader-mixed-list'):
            messages = response['output']['messages']
            assert [item['payload'] for item in messages] == (['one'] if name == 'reader-string' else ['one', 'two']), response
            assert messages[0]['sourceMap'] == {'pollId': 'sample', 'origin': 'seeded'}, response
            if name == 'reader-mixed-list':
                assert messages[1]['sourceMap']['origin'] == 'override', response
        elif name == 'writer-assignment-is-not-return':
            assert response['output']['response'] == {'status': 'SENT', 'data': '', 'statusMessage': '', 'error': ''}, response
        elif name == 'reader-source-defaults':
            maps = [item['sourceMap'] for item in response['output']['messages']]
            assert maps == [{'pollId': 'sample', 'origin': 'seeded', 'shared': {'count': 2}},
                            {'pollId': 'sample', 'origin': 'override', 'shared': {'count': 2}}], response
        elif name == 'reader-source-rebinding':
            assert response['output']['messages'][0]['sourceMap'] == {'pollId': 'sample', 'origin': 'seeded'}, response
        elif name == 'reader-source-cleared':
            assert response['output']['messages'][0]['sourceMap'] == {}, response
    return [item[0] for item in cases]


def live_case(api, child, profile, ingress, evidence, reader):
    name = 'reader-flow' if reader else 'http-flow'
    folder = evidence / name
    folder.mkdir()
    channel_id = str(uuid.uuid4())
    model = ET.fromstring(channel_xml(channel_id, ingress, profile['id'], marker=name, reader=reader))
    for path in ('sourceConnector/transformer/elements', 'destinationConnectors/connector/transformer/elements',
                 'destinationConnectors/connector/responseTransformer/elements'):
        model.find(path).clear()
    source = model.find('sourceConnector')
    destination = model.find('destinationConnectors/connector')
    if reader:
        source.find('properties/pollConnectorProperties/pollingFrequency').text = '3600000'
        source.find('properties/pythonScript').text = """assert 'poll' not in globals()
assert isinstance(sourceMap['pollId'], str)
local_value = 'from-reader'
sourceMap['local_value'] = local_value
sourceMap.put('variant', 'default')
globalChannelMap['poll_value'] = local_value
return [Message('accept', source_map={'variant': 'override'}),
        Message('source-reject'), Message('destination-reject')]
raise AssertionError('after Reader return')
"""
    metadata_check = """assert sourceMap['local_value'] == 'from-reader'
assert sourceMap['variant'] == ('override' if msg == 'accept' else 'default')
assert isinstance(sourceMap['pollId'], str)
try:
    sourceMap['forbidden'] = True
except TypeError:
    pass
else:
    raise AssertionError('Downstream sourceMap must remain read-only')
""" if reader else ''
    source_filter = metadata_check + """if msg == 'source-reject':
    return False
try:
    msg += '|source-filter'
finally:
    channelMap['source_finally'] = 'applied'
"""
    if not reader:
        source_filter += "return True\nraise AssertionError('after filter return')\n"
    source.find('filter/elements').append(rule(source_filter, profile['id']))
    destination.find('filter/elements').append(rule("""if msg.startswith('destination-reject'):
    return False
msg += '|destination-filter'
return True
""", profile['id']))
    source.find('transformer/elements').append(step(profile['id'], "assert channelMap['source_finally'] == 'applied'\nmsg += '|step'", 'Python mutation'))
    source.find('transformer/elements').append(javascript_step("msg = python_function(String(msg));", 'Python Function'))
    template_id, library_id = str(uuid.uuid4()), str(uuid.uuid4())
    template = make_template(template_id, """from __future__ import annotations
from dataclasses import dataclass
@dataclass
class Value:
    text: str
return Value(arguments[0] + '|function').text
raise AssertionError('after function return')
""")
    write(folder / 'template-save.json', api.call('/codeTemplates/' + template_id, 'PUT', template))
    library = {'list': {'codeTemplateLibrary': [{'@version': '4.6.0', 'id': library_id,
        'name': name, 'revision': 0, 'includeNewChannels': False, 'enabledChannelIds': {'string': channel_id},
        'disabledChannelIds': '', 'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}}
    write(folder / 'library-save.json', api.call('/codeTemplateLibraries', 'PUT', library))
    writer = """try:
    return Response(Status.SENT, payload + '|writer')
finally:
    connectorMap['writer_finally'] = 'applied'
raise AssertionError('after Writer return')
"""
    if reader:
        writer = "assert sourceMap['local_value'] == 'from-reader'\nassert sourceMap['variant'] == 'override'\nassert isinstance(sourceMap['pollId'], str)\nassert globalChannelMap['poll_value'] == 'from-reader'\n" + writer
        source.find('transformer/elements').append(javascript_step(
            "if (String(sourceMap.get('local_value')) !== 'from-reader') throw 'Missing Reader source metadata'; if (!sourceMap.containsKey('pollId')) throw 'Missing pollId';", 'JS source map'))
    destination.find('properties/pythonScript').text = writer
    destination.find('responseTransformer/elements').append(step(profile['id'],
        "assert connectorMap['writer_finally'] == 'applied'\nresponse.status = Status.SENT\nmsg += '|response'", 'Python response mutation'))
    body = ET.tostring(model, encoding='unicode')
    (folder / 'channel.xml').write_text(body)
    saved = api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
    assert saved is True or saved.get('boolean') is True, saved
    try:
        write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        if not reader:
            transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
            response = transport.call('/python/', 'POST', 'accept', 'text/plain')
            write(folder / 'response.json', response)
            assert response == 'accept|source-filter|step|function|destination-filter|writer|response', response
        count = 3 if reader else 1
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            assert child.poll() is None, 'Owned engine exited'
            if maximum(api, channel_id) == count:
                documents = [api.call('/channels/' + channel_id + '/messages/' + str(i)) for i in range(1, count + 1)]
                connectors = [connector_messages(item) for item in documents]
                if connectors[0].get(1, {}).get('status') == 'SENT' and (not reader or
                        connectors[1][0]['status'] == 'FILTERED' and connectors[2].get(1, {}).get('status') == 'FILTERED'):
                    break
            time.sleep(.2)
        else:
            raise AssertionError('Return flow did not reach expected native statuses')
        for i, document in enumerate(documents, 1):
            write(folder / ('message-' + str(i) + '.json'), document)
        assert 'accept|source-filter|step|function|destination-filter|writer|response' in str(connectors[0][1]), connectors[0]
    finally:
        write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    return name


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / Path(__file__).name)
    receipt['assertions'] = sample_cases(api, profile, evidence)
    for reader in (False, True):
        receipt['assertions'].append(live_case(api, child, profile, ingress, evidence, reader))
    receipt['status'] = 'passed'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--signer-sha256', help='Require and verify this signer on every plugin JAR')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
