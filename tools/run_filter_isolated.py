#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Native Python filter qualification on an owned stock engine, with shared cleanup."""
import argparse
from pathlib import Path
from release_contract import default_plugin
import shutil
import uuid
import xml.etree.ElementTree as ET
from live_engine_helpers import Api, element, javascript_step, require_listener, script_fields, step, write
from run_code_template_isolated import ROOT, make_channel, make_template, run
from run_live_engine_integration import connector_messages


def rule(source, profile, name='Python rule', operator='NONE', enabled=True, python=True):
    kind = 'com.mirth.connect.plugins.python.PythonRule' if python else 'com.mirth.connect.plugins.javascriptrule.JavaScriptRule'
    item = ET.Element(kind, version='4.6.0')
    for key, value in {'name': name, 'sequenceNumber': '0', 'enabled': str(enabled).lower(), 'operator': operator}.items():
        element(item, key, value)
    if python:
        script_fields(item, profile, source)
    else:
        element(item, 'script', source)
    return item


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / 'filter-scenario.py')
    results = []
    runtime = api.call('/extensions/python-runtime/runtime')
    selected = api.call('/extensions/python-runtime/profiles/' + profile['id'])
    sample = {'requestId': str(uuid.uuid4()), 'environmentProfileId': profile['id'],
        'expectedRegistryEpoch': selected['registryEpoch'], 'expectedEnvironmentRevision': selected['revision'],
        'expectedNodeId': runtime['nodeId'], 'role': 'source-filter', 'contractVersion': 1, 'timeoutMillis': 30000,
        'pythonScript': "channelMap['sample'] = True\nreturn False",
        'sampleContext': {'msg': {'kind': 'raw', 'text': 'sample'}}}
    preview = api.call('/extensions/python-runtime/scripts/test', 'POST', sample)
    write(evidence / 'sample-filter-preview.json', preview)
    assert preview['status'] == 'success' and preview['output']['accepted'] is False, preview
    assert preview['output']['mapChanges']['channelMap']['set']['sample'] is True, preview
    for phase, metadata in [('source', 0), ('destination', 1)]:
        for case in ['accept', 'reject', 'nonbool', 'exception', 'short-and', 'short-or', 'mixed-not', 'disabled', 'json', 'xml', 'false-mutation', 'invalid-xml', 'same-source', 'function-step']:
            channel_id = str(uuid.uuid4())
            folder = evidence / (phase + '-' + case)
            folder.mkdir()
            model = ET.fromstring(make_channel(channel_id, ingress))
            model.find('name').text = 'Py filter ' + phase + ' ' + case
            for path in ['sourceConnector/transformer/elements', 'destinationConnectors/connector/transformer/elements', 'destinationConnectors/connector/responseTransformer/elements']:
                model.find(path).clear()
            connector = model.find('sourceConnector' if metadata == 0 else 'destinationConnectors/connector')
            rules = connector.find('filter/elements')
            rules.clear()
            source = "logger.info('filter-qualified')\nchannelMap['filterSeen'] = True\nmsg += '|filter'\nreturn True"
            expected_status = 'TRANSFORMED' if metadata == 0 else 'SENT'
            expected_response = 'input|filter'
            payload = 'input'
            transformer = connector.find('transformer')
            if case in ('json', 'xml', 'invalid-xml'):
                datatype = 'JSON' if case == 'json' else 'XML'
                package, prefix = ('json', 'JSON') if datatype == 'JSON' else ('xml', 'XML')
                for side in ('inbound', 'outbound'):
                    transformer.find(side + 'DataType').text = datatype
                    props = transformer.find(side + 'Properties')
                    props.clear()
                    props.set('class', f'com.mirth.connect.plugins.datatypes.{package}.{prefix}DataTypeProperties')
                    props.set('version', '4.6.0')
                    if datatype == 'XML':
                        serialization = element(props, 'serializationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.xml.XMLSerializationProperties', 'version': '4.6.0'})
                        element(serialization, 'stripNamespaces', 'false')
                if case == 'json':
                    payload = '{"trace":["input"]}'
                    source = "msg['trace'].append('filter')\nchannelMap['ordered'] = True\nreturn True"
                    transformer.find('elements').append(javascript_step("if (!channelMap.get('ordered')) throw 'Filter ran too late'; msg.trace.push('transformer');", 'Following transformer'))
                    expected_response = {'trace': ['input', 'filter', 'transformer']}
                elif case == 'xml':
                    payload = '<root><input/></root>'
                    source = "msg = '<root><input/><filter/></root>'\nreturn True"
                    transformer.find('elements').append(javascript_step("msg.appendChild(new XML('<transformer/>'));", 'Following transformer'))
                    expected_response = None
                else:
                    payload = '<root/>'
                    source = "connectorMap['partial'] = 'must-not-apply'\nmsg = '<broken>'\nreturn True"
                    expected_status = 'ERROR'
            elif case == 'false-mutation':
                source = "msg += '|false'\nchannelMap['falseMarker'] = 'false-applied'\nreturn False"
                expected_status = 'FILTERED'
            elif case == 'same-source':
                # Reuse a helper and mutation body across the two roles.
                source = "def accept():\n    return True\nmsg += '|shared'"
                transformer.find('elements').append(step(profile['id'], source, 'Same source transformer'))
                source += "\nreturn accept()"
                expected_response = 'input|shared|shared'
            elif case == 'function-step':
                source = "msg += '|filter'\nreturn True"
                template_id, library_id = str(uuid.uuid4()), str(uuid.uuid4())
                template = make_template(template_id, "return arguments[0] + '|function'")
                write(folder / 'template-save.json', api.call('/codeTemplates/' + template_id, 'PUT', template))
                library = {'list': {'codeTemplateLibrary': [{'@version': '4.6.0', 'id': library_id,
                    'name': 'Python filter mixed phase', 'revision': 0, 'includeNewChannels': False,
                    'enabledChannelIds': {'string': channel_id}, 'disabledChannelIds': '',
                    'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}}
                write(folder / 'library-save.json', api.call('/codeTemplateLibraries', 'PUT', library))
                transformer.find('elements').append(step(profile['id'], "msg += '|step'", 'Python step'))
                transformer.find('elements').append(javascript_step('msg = python_function(msg);', 'Python Function call'))
                expected_response = 'input|filter|step|function'
            if case == 'reject': source = 'return False'; expected_status = 'FILTERED'
            elif case == 'nonbool': source = "channelMap['partial'] = 'must-not-apply'\nreturn 1"; expected_status = 'ERROR'
            elif case == 'exception': source = "raise ValueError('filter failure')"; expected_status = 'ERROR'
            elif case in ('short-and', 'short-or'):
                first = 'false' if case == 'short-and' else 'true'
                rules.append(rule('return ' + first + ';', profile['id'], python=False))
                rules.append(rule("raise AssertionError('must short circuit')", profile['id'], operator='AND' if case == 'short-and' else 'OR'))
                expected_status = 'FILTERED' if case == 'short-and' else expected_status
                expected_response = 'input'
            elif case == 'mixed-not':
                rules.append(rule("channelMap.put('before', 'yes'); return true;", profile['id'], python=False))
                source = "assert channelMap['before'] == 'yes'\nmsg += '|filter'\nreturn not False"
            elif case == 'disabled':
                rules.append(rule("raise AssertionError('disabled')", profile['id'], enabled=False))
                expected_response = 'input'
            if case in ('nonbool', 'invalid-xml'):
                target = 'channelMap' if case == 'nonbool' else 'connectorMap'
                rules.append(rule(target + ".put('keep', 'original'); return true;", profile['id'], python=False))
            if case == 'accept':
                transformer.find('elements').append(javascript_step("if (!channelMap.get('filterSeen')) throw 'Filter ran too late';", 'Following transformer'))
            if case not in ('short-and', 'short-or', 'disabled'):

                rules.append(rule(source, profile['id'], operator='AND' if case in ('mixed-not', 'nonbool', 'invalid-xml') else 'NONE'))
            xml = ET.tostring(model, encoding='unicode')
            (folder / 'channel-submitted.xml').write_text(xml)
            saved = False
            try:
                response = api.call('/channels/' + channel_id, 'PUT', xml, 'application/xml')
                assert response is True or response.get('boolean') is True, response
                saved = True
                write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
                transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
                response = transport.call('/python/', 'POST', payload, 'text/plain')
                write(folder / 'response.json', response)
                message = api.call('/channels/' + channel_id + '/messages/1')
                write(folder / 'message.json', message)
                connectors = connector_messages(message)
                assert connectors[metadata]['status'] == expected_status, message
                if expected_status in ('SENT', 'TRANSFORMED'):
                    if case == 'xml':
                        parsed = ET.fromstring(response)
                        assert [node.tag for node in parsed] == ['input', 'filter', 'transformer'], response
                    else:
                        assert response == expected_response, response
                if case in ('nonbool', 'invalid-xml'):
                    field = 'channelMapContent' if case == 'nonbool' else 'connectorMapContent'
                    assert field in connectors[metadata], message
                    assert 'original' in str(connectors[metadata][field]), message
                    assert 'must-not-apply' not in str(connectors[metadata][field]), message
                if case == 'false-mutation':
                    assert 'channelMapContent' in connectors[metadata], message
                    assert 'false-applied' in str(connectors[metadata]['channelMapContent']), message
                write(folder / 'saved-channel.json', api.call('/channels/' + channel_id))
                results.append({'case': phase + '-' + case, 'status': 'passed'})
            finally:
                if saved:
                    write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    logs = (evidence / 'owned-runtime/oie/logs/mirth.log').read_text().splitlines()
    matched = [line for line in logs if line.endswith('] filter-qualified')]
    assert len(matched) == 2 and all('] filter: [Py filter ' in line for line in matched), matched
    write(evidence / 'filter-log-observations.json', matched)
    write(evidence / 'filter-results.json', results)
    receipt.update(status='passed', assertions=[item['case'] for item in results])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
