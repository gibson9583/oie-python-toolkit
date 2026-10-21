#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify Reader Message/RawMessage mapping on an owned stock engine.

Reuse the isolated runner's process receipts, immutable pins and resource
cleanup. No reference installation or shared database is accessed.
"""
import argparse
import copy
from pathlib import Path
from release_contract import default_plugin
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import channel_xml, element, port, write
from run_code_template_isolated import ROOT, run
from run_live_engine_integration import connector_messages


def cases():
    metadata = "source_map={'origin':'reader', 'nested':{'id':42}, 'bytes':b'\\x00\\xff', 'expected':%r}"
    defaults = "assert 'poll' not in globals()\nassert isinstance(sourceMap['pollId'], str)\nsourceMap.update({'origin':'reader','nested':{'id':42},'bytes':b'\\x00\\xff'})\n"
    messages = ["Message('', " + metadata % '' + ")",
                "Message(b'', " + metadata % '' + ", destination_ids=[])",
                "Message(b'\\x00\\xff', " + metadata % 'AP8=' + ", destination_ids=[2])",
                "Message('subset', " + metadata % 'subset' + ", destination_ids=[1])"]
    return [
        dict(name='text-bytes-routing', expression='[' + ','.join(messages) + ']', routes=[{0, 1, 2}, {0}, {0, 2}, {0, 1}], raw=['', '', 'AP8=', 'subset']),
        dict(name='single', expression="Message('single', " + metadata % 'single' + ")", routes=[{0, 1, 2}], raw=['single']),
        dict(name='bare-string', prefix=defaults + "sourceMap['expected'] = 'single'\n", expression="'single'", routes=[{0, 1, 2}], raw=['single']),
        dict(name='string-list', prefix=defaults, expression="['one', '', 'two']", routes=[{0, 1, 2}, {0, 1, 2}], raw=['one', 'two']),
        dict(name='mixed-list', prefix=defaults, expression="['one', Message('two', source_map={'origin':'reader'}, destination_ids=[2])]", routes=[{0, 1, 2}, {0, 2}], raw=['one', 'two']),
        dict(name='empty-string', expression="''", routes=[]),
        dict(name='string-batch', prefix=defaults, expression="'one\\ntwo\\n'", routes=[{0, 1, 2}, {0, 1, 2}], raw=['one', 'two'], batch=True),
        dict(name='string-then-invalid', expression="['first', None]", routes=[], error='not str or a Message'),
        dict(name='none', expression='None', routes=[]),
        dict(name='empty-list', expression='[]', routes=[]),
        dict(name='batch', expression="Message('one\\ntwo\\n', source_map={'origin':'reader','nested':{'id':42},'bytes':b'\\x00\\xff'}, destination_ids=[2])",
             routes=[{0, 2}, {0, 2}], raw=['one', 'two'], batch=True),
        dict(name='batch-binary', expression="[Message('first'), Message(b'bad')]", routes=[], batch=True, error='Batch processing does not support binary'),
        dict(name='bad-payload', expression="[Message('first'), Message(None)]", routes=[], error='payload must be str or bytes'),
        dict(name='reserved-map', expression="[Message('first'), Message('bad', source_map={'batchId':'owned'})]", routes=[], error='reserved engine key'),
        dict(name='disabled-destination', expression="[Message('first'), Message('bad', destination_ids=[3])]", routes=[], error='unknown or disabled'),
        dict(name='invalid-map-value', expression="[Message('first'), Message('bad', source_map={'java':object()})]", routes=[], error='unsupported'),
        dict(name='source-defaults', prefix=defaults, expression="[Message('one'), Message('two')]", routes=[{0, 1, 2}, {0, 1, 2}], raw=['one', 'two']),
        dict(name='source-override', prefix=defaults + "sourceMap['origin'] = 'default'\n", expression="Message('one', source_map={'origin':'reader'})", routes=[{0, 1, 2}], raw=['one']),
        dict(name='source-defaults-batch', prefix=defaults, expression="Message('one\\ntwo\\n', destination_ids=[2])", routes=[{0, 2}, {0, 2}], raw=['one', 'two'], batch=True),
        dict(name='invalid-default-none', prefix="sourceMap['batchId'] = 'owned'\n", expression='None', routes=[], error='reserved engine key'),
    ]


def maximum(api, channel_id):
    value = api.call('/channels/' + channel_id + '/messages/maxMessageId')
    return int(value.get('long', 0) if isinstance(value, dict) else value)


def execute_case(api, child, profile, evidence, case):
    folder = evidence / case['name']; folder.mkdir()
    channel_id = str(uuid.uuid4())
    model = ET.fromstring(channel_xml(channel_id, port(), profile['id'], marker=case['name'], reader=True))
    model.find('name').text = 'Py Reader ' + case['name']
    model.find('nextMetaDataId').text = '4'
    model.find('sourceConnector/properties/pollConnectorProperties/pollingFrequency').text = '3600000'
    source = case.get('prefix', '') + "print('reader-case-" + channel_id + "')\nreturn " + case['expression'] + "\n"
    model.find('sourceConnector/properties/pythonScript').text = source
    destinations = model.find('destinationConnectors')
    first = destinations.find('connector')
    for number in (2, 3):
        destination = copy.deepcopy(first)
        destination.find('metaDataId').text = str(number)
        destination.find('name').text = 'Destination ' + str(number)
        destination.find('enabled').text = 'true' if number == 2 else 'false'
        destinations.append(destination)
    for destination in destinations:
        for path in ('transformer/elements', 'responseTransformer/elements'):
            destination.find(path).clear()
        destination.find('properties/pythonScript').text = r"""
assert sourceMap['origin'] == 'reader'
assert sourceMap['nested']['id'] == 42
assert sourceMap['bytes'] == b'\x00\xff'
assert isinstance(sourceMap['pollId'], str)
if sourceMap.get('expected') is not None:
    assert payload == sourceMap['expected'], repr(payload)
else:
    assert payload in ('one', 'two'), repr(payload)
return Response(data=payload)
"""
    if case.get('batch'):
        model.find('sourceConnector/properties/sourceConnectorProperties/processBatch').text = 'true'
        inbound = model.find('sourceConnector/transformer/inboundProperties')
        properties = element(inbound, 'batchProperties', **{'class': 'com.mirth.connect.plugins.datatypes.raw.RawBatchProperties', 'version': '4.6.0'})
        element(properties, 'splitType', 'JavaScript')
        element(properties, 'batchScript', 'return reader.readLine();')
    body = ET.tostring(model, encoding='unicode')
    (folder / 'channel-submitted.xml').write_text(body)
    write(folder / 'case.json', {**case, 'routes': [sorted(route) for route in case['routes']]})
    saved = api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
    assert saved is True or isinstance(saved, dict) and saved.get('boolean') is True, saved
    try:
        write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            assert child.poll() is None, 'Owned engine exited'
            logs = '\n'.join(path.read_text(errors='replace') for path in (evidence / 'owned-runtime/oie/logs').glob('*.log'))
            if case.get('error'):
                # Match this channel's failure, rather than an older case's log.
                failures = [line for line in logs.splitlines() if channel_id in line and 'Python Reader poll failed' in line]
                if failures and any(case['error'].lower() in line.lower() for line in failures):
                    (folder / 'expected-error.txt').write_text('\n'.join(failures) + '\n')
                    break
            elif not case['routes']:
                if 'reader-case-' + channel_id in logs:
                    break
            elif maximum(api, channel_id) == len(case['routes']):
                documents = [api.call('/channels/' + channel_id + '/messages/' + str(index))
                             for index in range(1, len(case['routes']) + 1)]
                if all(set(connector_messages(document)) == expected and
                       all(message['status'] == 'SENT' for number, message in connector_messages(document).items() if number)
                       for document, expected in zip(documents, case['routes'])):
                    break
            time.sleep(.25)
        else:
            raise AssertionError('Reader case did not reach expected outcome: ' + case['name'])
        write(folder / 'stop.json', api.call('/channels/' + channel_id + '/_stop?returnErrors=true', 'POST'))
        assert maximum(api, channel_id) == len(case['routes']), 'Unexpected partial admission or duplicate poll'
        for index, expected in enumerate(case['routes'], 1):
            document = api.call('/channels/' + channel_id + '/messages/' + str(index))
            write(folder / ('message-' + str(index) + '.json'), document)
            actual = connector_messages(document)
            assert set(actual) == expected, document
            assert all(message['status'] == 'SENT' for number, message in actual.items() if number), document
            # Stock XML-to-JSON API serializes an empty text element as null.
            content = actual[0]['raw'].get('content')
            assert ('' if content is None else content) == case['raw'][index - 1], document
        write(folder / 'messages.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    finally:
        write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / 'reader-message-scenario.py')
    receipt['assertions'] = []
    for case in cases():
        execute_case(api, child, profile, evidence, case)
        receipt['assertions'].append(case['name'])
        print('Reader case passed: ' + case['name'], flush=True)
    receipt['status'] = 'passed'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
