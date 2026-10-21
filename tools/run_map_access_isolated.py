#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify complete Python maps on one owned stock engine and private database."""
import argparse
from pathlib import Path
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import Api, channel_xml, javascript_step, port, require_listener, write
from run_code_template_isolated import ROOT, make_channel, make_template, run
from run_xml_roundtrip_isolated import PHASES, scenario as transformer_scenario


def map_case(phase, failure=None):
    before = """
globalMap.put('unselected', 'global'); globalMap.put('remove', 'old');
globalChannelMap.put('unselected', 'channel');
channelMap.put('before', 'channel'); connectorMap.put('before', 'connector'); responseMap.put('before', 'response');
responseMap.put('Destination', 'literal-name');
"""
    source = """
assert configurationMap['map-site'] == 'local'
assert globalMap.get('unselected') == 'global'
assert globalMap.get('missing') is None
assert globalMap.getVariables().get('unselected') == 'global'
assert globalMap.getVariables().containsKey('unselected')
assert globalChannelMap['unselected'] == 'channel'
assert sourceMap.containsKey('destinationSet')
assert channelMap['before'] == 'channel' and connectorMap['before'] == 'connector' and responseMap['before'] == 'response'
assert 'inputs' not in globals() and 'channel_map' not in globals() and 'global_map' not in globals()
assert channelMap.get('destinationSet') is sourceMap.get('destinationSet')
assert not channelMap.containsKey('destinationSet')
assert 'destinationSet' not in channelMap.keySet()
had_response = responseMap.containsKey('d1')
previous_response = responseMap.get('d1')
assert responseMap.remove('Destination') == 'literal-name'
responseMap.put('d1', 'by-id')
assert responseMap.get('Destination') == 'by-id'
assert responseMap.containsKey('Destination')
assert responseMap.remove('Destination') is None
assert responseMap.get('d1') == 'by-id'
assert responseMap.remove('d1') == 'by-id'
if had_response:
    responseMap.put('d1', previous_response)
globalMap.put('written', phase)
globalChannelMap.put('written', phase)
channelMap.put('written', phase)
connectorMap.put('written', phase)
responseMap.put('written', phase)
globalMap.remove('remove')
"""
    after = f"""
if (String(globalMap.get('written')) !== '{phase}-transformer' || String(globalChannelMap.get('written')) !== '{phase}-transformer') throw 'Missing shared write';
if (String(channelMap.get('written')) !== '{phase}-transformer' || String(connectorMap.get('written')) !== '{phase}-transformer' || String(responseMap.get('written')) !== '{phase}-transformer') throw 'Missing message write';
if (globalMap.containsKey('remove')) throw 'Global delete was not applied';
if (responseMap.keySet().contains('Destination')) throw 'Literal response name delete was not applied';
msg = 'maps-ok';
"""
    if failure:
        source += {
            'exception': "raise RuntimeError('expected map rejection')\n",
            'null': "globalMap['invalid'] = None\n",
            'configuration': "configurationMap['map-site'] = 'changed'\n",
            'source': "sourceMap['invalid'] = 'changed'\n",
            'document': "msg = object()\n",
        }[failure]
        before += "globalMap.remove('written'); globalChannelMap.remove('written'); try {\n"
        after = """
throw 'Expected map rejection'; } catch (failure) { if (String(failure).indexOf('PythonStepException') < 0) throw failure; }
if (String(msg) !== 'input') throw 'Changed document escaped rejection';
if (globalMap.containsKey('written') || globalChannelMap.containsKey('written') || !globalMap.containsKey('remove')) throw 'Shared changes escaped rejection';
if (channelMap.containsKey('written') || connectorMap.containsKey('written') || responseMap.containsKey('written')) throw 'Message changes escaped rejection';
if (String(responseMap.get('Destination')) !== 'literal-name') throw 'Response delete escaped rejection';
msg = 'rollback-ok';
"""
    return dict(name='maps-' + phase + ('-' + failure if failure else ''), phase=phase, datatype='RAW',
        payload='input', template=None, before=before, python=source, after=after,
        expectedResponse='rollback-ok' if failure else 'maps-ok', repetitions=2)


def saved_channel(api, child, model, channel_id, listener, folder, expected):
    folder.mkdir()
    body = ET.tostring(model, encoding='unicode')
    (folder / 'channel-submitted.xml').write_text(body)
    saved = False
    try:
        value = api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
        assert value is True or isinstance(value, dict) and value.get('boolean') is True, value
        saved = True
        write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        transport = Api(f'http://127.0.0.1:{listener}', lambda: require_listener(child, listener))
        for index in range(2):
            response = transport.call('/python/', 'POST', 'input', 'text/plain')
            write(folder / f'response-{index}.json', response)
            assert response == expected, response
        write(folder / 'messages.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    finally:
        if saved:
            write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))


def functions(api, child, evidence):
    channel_id, template_id, library_id = [str(uuid.uuid4()) for _ in range(3)]
    source = """
assert configurationMap['map-site'] == 'local'
assert sourceMap.containsKey('destinationSet')
assert channelMap['before'] == phase
globalMap.put('function', phase)
globalChannelMap.put('function', phase)
channelMap.put('function', phase)
connectorMap.put('function', phase)
responseMap.put('function', phase)
return arguments[0] + '|' + phase
"""
    model = make_template(template_id, source)
    write(evidence / 'function-submitted.json', model)
    api.call('/codeTemplates/' + template_id, 'PUT', model)
    api.call('/codeTemplateLibraries', 'PUT', {'list': {'codeTemplateLibrary': [{
        '@version': '4.6.0', 'id': library_id, 'name': 'Map function library', 'revision': 0,
        'description': 'Owned map qualification', 'includeNewChannels': False,
        'enabledChannelIds': {'string': channel_id}, 'disabledChannelIds': '',
        'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}})
    listener = port()
    channel = ET.fromstring(make_channel(channel_id, listener))
    for phase, path in PHASES.items():
        script = f"""
channelMap.put('before', '{phase}-transformer');
msg = python_function(String(msg), '{phase}');
if (String(globalMap.get('function')) !== '{phase}-transformer' || String(globalChannelMap.get('function')) !== '{phase}-transformer') throw 'Missing function shared write';
if (String(channelMap.get('function')) !== '{phase}-transformer' || String(connectorMap.get('function')) !== '{phase}-transformer' || String(responseMap.get('function')) !== '{phase}-transformer') throw 'Missing function message write';
"""
        elements = channel.find(path + '/elements'); elements.clear(); elements.append(javascript_step(script, 'Function maps'))
    saved_channel(api, child, channel, channel_id, listener, evidence / 'functions',
        'input|source-transformer|destination-transformer|response-transformer')


def writer(api, child, profile, evidence):
    channel_id, listener = str(uuid.uuid4()), port()
    model = ET.fromstring(channel_xml(channel_id, listener, profile['id'], marker='map-writer'))
    for path in PHASES.values(): model.find(path + '/elements').clear()
    model.find('sourceConnector/transformer/elements').append(javascript_step(
        "channelMap.put('source', 'engine'); globalMap.put('writer_seed', 'engine'); globalChannelMap.put('writer_seed', 'channel');", 'Writer inputs'))
    model.find('destinationConnectors/connector/properties/pythonScript').text = """
assert configurationMap['map-site'] == 'local' and globalMap['writer_seed'] == 'engine'
assert globalChannelMap['writer_seed'] == 'channel' and channelMap['source'] == 'engine'
assert sourceMap.containsKey('destinationSet')
globalMap.put('writer', 'python')
globalChannelMap.put('writer', 'python')
channelMap.put('writer', 'python')
connectorMap.put('writer', 'python')
responseMap.put('writer', 'python')
return 'writer-ok'
"""
    model.find('destinationConnectors/connector/responseTransformer/elements').append(javascript_step("""
if (String(globalMap.get('writer')) !== 'python' || String(globalChannelMap.get('writer')) !== 'python') throw 'Writer shared changes missing';
if (String(channelMap.get('writer')) !== 'python' || String(connectorMap.get('writer')) !== 'python' || String(responseMap.get('writer')) !== 'python') throw 'Writer message changes missing';
""", 'Check Writer maps'))
    saved_channel(api, child, model, channel_id, listener, evidence / 'writer', 'writer-ok')


def reader(api, child, profile, evidence):
    folder = evidence / 'reader'; folder.mkdir()
    channel_id = str(uuid.uuid4())
    model = ET.fromstring(channel_xml(channel_id, port(), profile['id'], marker='map-reader', reader=True))
    for path in PHASES.values(): model.find(path + '/elements').clear()
    model.find('sourceConnector/properties/pythonScript').text = """
from oie_python import Message
assert configurationMap['map-site'] == 'local'
assert 'channelMap' not in globals() and 'poll' not in globals()
assert isinstance(sourceMap['pollId'], str)
assert globalMap['writer'] == 'python'
globalMap.put('reader', 'python')
globalChannelMap.put('reader', 'python')
return Message('reader-input', source_map={'origin': 'reader'})
"""
    model.find('destinationConnectors/connector/properties/pythonScript').text = """
assert globalMap['reader'] == 'python' and globalChannelMap['reader'] == 'python'
assert sourceMap['origin'] == 'reader'
print('map-reader-dispatched')
return 'reader-ok'
"""
    body = ET.tostring(model, encoding='unicode'); (folder / 'channel-submitted.xml').write_text(body)
    api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
    try:
        write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            logs = '\n'.join(path.read_text(errors='replace') for path in (evidence / 'owned-runtime/oie/logs').glob('*.log'))
            if 'map-reader-dispatched' in logs: break
            time.sleep(.2)
        else: raise AssertionError('Reader did not dispatch its result with committed maps')
        write(folder / 'messages.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    finally:
        write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / 'map-scenario.py')
    write(evidence / 'removed-selector-endpoint.json',
        api.call('/extensions/python-runtime/context-inputs', status=(404,)))
    configuration = '<map><entry><string>map-site</string><com.mirth.connect.util.ConfigurationProperty><value>local</value><comment>Owned map qualification</comment></com.mirth.connect.util.ConfigurationProperty></entry></map>'
    api.call('/server/configurationMap', 'PUT', configuration, 'application/xml')
    write(evidence / 'configuration-map.json', api.call('/server/configurationMap'))
    cases = [map_case(phase) for phase in PHASES] + [map_case('source', failure) for failure in ['exception', 'null', 'configuration', 'source', 'document']]
    transformer_scenario(api, child, profile, ingress, evidence, receipt, cases=cases)
    receipt['status'] = 'running'
    functions(api, child, evidence)
    writer(api, child, profile, evidence)
    reader(api, child, profile, evidence)
    receipt['assertions'] += ['selector-endpoint-removed', 'function-maps-in-three-phases', 'writer-maps-before-response-transform', 'reader-maps-before-dispatch']
    receipt['status'] = 'passed'
    print('All direct map surfaces passed', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
