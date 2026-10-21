#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Prove JavaScript/Python XML round trips on an owned stock engine.

Uses the existing isolated-engine lifecycle, evidence capture, and cleanup.
Never accesses the reference engine. --case can select a focused regression.
"""
import argparse
import json
from pathlib import Path
from release_contract import default_plugin
import shutil
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import Api, element, javascript_step, port, require_listener, step, write
from run_code_template_isolated import ROOT, make_channel, run

PHASES = {
    'source': 'sourceConnector/transformer',
    'destination': 'destinationConnectors/connector/transformer',
    'response': 'destinationConnectors/connector/responseTransformer',
}


def xml_case(phase, namespace='', fragments=False, template=True):
    name = ('xmllist-namespaces' if fragments and namespace else 'xmllist' if fragments else 'namespaces' if namespace else 'xml') + '-' + phase
    if not template:
        name += '-no-template'
    declaration = f' xmlns="{namespace}"' if namespace else ''
    plain = ' xmlns=""' if namespace else ''
    def document(seed):
        children = ''.join(f'<plain{plain}>{seed}{index}</plain>' for index in range(2 if fragments else 1))
        return f'<root{declaration}>{children}</root>'
    variables = ['msg', 'tmp'] if template else ['msg']
    before, after = [], []
    for variable in variables:
        seed = 'input' if variable == 'msg' else 'template'
        before.append(f"if (typeof {variable} !== 'xml') throw 'Expected XML {variable}';")
        before.append(f"if (String({variable}.name().uri) !== {json.dumps(namespace)}) throw 'Input root namespace';")
        before.append(f"for each (var node in {variable}.children()) {{ if (String(node.name().uri) !== '') throw 'Input child namespace'; node.setChildren(String(node) + '|js-before'); }}")
        if fragments:
            before.append(f'{variable} = {variable}.children();')
        after.append(f"if (Object.prototype.toString.call({variable}) !== '[object {'XMLList' if fragments else 'XML'}]') throw 'Changed E4X type';")
        if not fragments:
            after.append(f"if (String({variable}.name().uri) !== {json.dumps(namespace)}) throw 'Changed root namespace';")
        nodes = variable if fragments else variable + '.children()'
        after.append(f"if ({nodes}.length() !== {2 if fragments else 1}) throw 'Changed child count';")
        after.append(f"for (var index = 0; index < {nodes}.length(); index++) {{ var node = {nodes}[index]; if (String(node.name().uri) !== '') throw 'Changed {variable} child namespace'; if (String(node) !== '{seed}' + index + '|js-before|python') throw 'Lost {variable} mutation'; node.setChildren(String(node) + '|js-after'); }}")
        after.append(f"channelMap.put('{name}-{variable}', {variable}.toXMLString());")
    after.append(f"if (String((<probe/>).name().uri) !== {json.dumps(namespace)}) throw 'Caller default namespace changed';")
    source = f'''import xml.etree.ElementTree as ET
assert phase == '{phase}-transformer'
assert has_tmp is {template}
for name in {variables!r}:
    text = globals()[name]
    root = ET.fromstring('<wrap>' + text + '</wrap>' if {fragments} else text)
    assert root.tag == {'wrap' if fragments else '{' + namespace + '}root' if namespace else 'root'!r}
    for node in root:
        assert node.tag == 'plain'
        assert node.text.endswith('|js-before')
        node.text += '|python'
    globals()[name] = ''.join(ET.tostring(node, encoding='unicode') for node in root) if {fragments} else root
'''
    return dict(name=name, phase=phase, datatype='XML', payload=document('input'),
        template=document('template') if template else None, before='\n'.join(before),
        python=source, after='\n'.join(after), namespace=namespace, fragments=fragments)


def hl7_case():
    payload = 'MSH|^~\\&|SOURCE|SEND|DEST|RECV|202610191200||ADT^A01|1|P|2.5\rPID|1||INPUT||input\r'
    before, after = [], []
    for variable, seed in [('msg', 'input'), ('tmp', 'template')]:
        field = f"{variable}['PID']['PID.5']['PID.5.1']"
        before.append(f"if (String({variable}.name()) !== 'HL7Message') throw 'Expected internal HL7 XML'; {field} = String({field}) + '-js-before';")
        after.append(f"if (String({field}) !== '{seed}-js-before-python') throw 'Lost HL7 {variable} mutation'; {field} = String({field}) + '-js-after'; channelMap.put('hl7-{variable}', {variable}.toXMLString());")
    source = '''import xml.etree.ElementTree as ET
assert has_tmp
for name in ['msg', 'tmp']:
    root = ET.fromstring(globals()[name])
    assert root.tag == 'HL7Message'
    node = root.find('PID/PID.5/PID.5.1')
    assert node.text.endswith('-js-before')
    node.text += '-python'
    globals()[name] = root
'''
    return dict(name='hl7-source', phase='source', datatype='HL7V2', payload=payload,
        template=payload.replace('INPUT', 'TEMPLATE').replace('input', 'template'),
        before='\n'.join(before), python=source, after='\n'.join(after))


def replacement_case(whitespace=False):
    case = xml_case('source')
    documents = ([('msg', 'message', '\n<message><value>changed-msg</value></message>\n'),
                  ('tmp', 'template', '\n<template><value>changed-tmp</value></template>\n')]
                 if whitespace else [('msg', 'a', '<a/>'), ('tmp', 'b', '<b/>')])
    source = '''import xml.etree.ElementTree as ET
assert phase == 'source-transformer' and has_tmp
for name, seed in [('msg', 'input'), ('tmp', 'template')]:
    root = ET.fromstring(globals()[name])
    assert root.tag == 'root' and root.find('plain').text == seed + '0|js-before'
'''
    after = []
    for variable, tag, text in documents:
        source += f'{variable} = {text!r}\n'
        after.append(f"if (typeof {variable} !== 'xml' || {variable}.nodeKind() !== 'element' || String({variable}.localName()) !== '{tag}' || String({variable}.name().uri) !== '') throw 'Invalid restored {variable} element';")
        if whitespace:
            after.append(f"if (String({variable}.value) !== 'changed-{variable}') throw 'Lost {variable} child';")
        after.append(f"{variable}.@marker = 'js-after'; channelMap.put('replacement-{variable}', {variable}.toXMLString());")
    case.update(name=('whitespace' if whitespace else 'short') + '-xml-source', python=source,
        after='\n'.join(after), replacementRoot=documents[1][1], replacementValue='changed-tmp' if whitespace else None)
    return case


def channel(case, channel_id, ingress, profile_id):
    model = ET.fromstring(make_channel(channel_id, ingress))
    model.find('name').text = 'Python ' + case['name']
    for path in PHASES.values():
        model.find(path + '/elements').clear()
    transformer = model.find(PHASES[case['phase']])
    datatype = case['datatype']
    package, prefix = {'XML': ('xml', 'XML'), 'HL7V2': ('hl7v2', 'HL7v2'),
        'JSON': ('json', 'JSON'), 'RAW': ('raw', 'Raw')}[datatype]
    for side in ['inbound', 'outbound']:
        transformer.find(side + 'DataType').text = datatype
        properties = transformer.find(side + 'Properties')
        properties.clear()
        properties.set('class', f'com.mirth.connect.plugins.datatypes.{package}.{prefix}DataTypeProperties')
        properties.set('version', '4.6.0')
        if datatype in ('JSON', 'RAW'):
            continue
        serialization = element(properties, 'serializationProperties', **{'class': f'com.mirth.connect.plugins.datatypes.{package}.{prefix}SerializationProperties', 'version': '4.6.0'})
        element(serialization, 'stripNamespaces', 'false')
        if datatype == 'HL7V2':
            for key, value in {'handleRepetitions': 'true', 'handleSubcomponents': 'true', 'useStrictParser': 'false', 'useStrictValidation': 'false', 'segmentDelimiter': '\\r', 'convertLineBreaks': 'true'}.items():
                element(serialization, key, value)
            deserialization = element(properties, 'deserializationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DeserializationProperties', 'version': '4.6.0'})
            for key, value in {'useStrictParser': 'false', 'useStrictValidation': 'false', 'segmentDelimiter': '\\r'}.items():
                element(deserialization, key, value)
    if case['template'] is not None:
        element(transformer, 'outboundTemplate', case['template'])
    elements = transformer.find('elements')
    for index, item in enumerate([javascript_step(case['before'], 'JavaScript before'), step(profile_id, case['python'], 'Python XML'), javascript_step(case['after'], 'JavaScript after')]):
        item.find('sequenceNumber').text = str(index)
        elements.append(item)
    return ET.tostring(model, encoding='unicode')


def verify(case, response):
    if 'expectedResponse' in case:
        assert response == case['expectedResponse'], response
        return
    assert isinstance(response, str), response
    if case['datatype'] == 'HL7V2':
        assert 'PID|1||TEMPLATE||template-js-before-python-js-after' in response, response
        return
    if 'replacementRoot' in case:
        root = ET.fromstring(response)
        assert root.tag == case['replacementRoot'] and root.attrib == {'marker': 'js-after'}, response
        assert root.findtext('value') == case['replacementValue'], response
        return
    root = ET.fromstring('<wrap>' + response + '</wrap>' if case['fragments'] else response)
    expected_root = 'wrap' if case['fragments'] else '{' + case['namespace'] + '}root' if case['namespace'] else 'root'
    assert root.tag == expected_root, response
    assert len(root) == (2 if case['fragments'] else 1), response
    seed = 'template' if case['template'] is not None else 'input'
    for index, node in enumerate(root):
        assert node.tag == 'plain', response
        assert node.text == f'{seed}{index}|js-before|python|js-after', response


CASES = ([xml_case(phase) for phase in PHASES] + [xml_case(phase, namespace='urn:r') for phase in PHASES]
    + [xml_case('source', fragments=True), xml_case('source', namespace='urn:r', fragments=True),
       xml_case('source', template=False), hl7_case(), replacement_case(), replacement_case(whitespace=True)])
def scenario(api, child, profile, ingress, evidence, receipt, selected=None, cases=CASES):
    shutil.copyfile(__file__, evidence / 'scenario.py')
    shutil.copyfile(ROOT / 'tools/integration-channel.xml', evidence / 'integration-channel.xml')
    results = []
    for case in cases:
        if selected and case['name'] not in selected:
            continue
        folder = evidence / case['name']
        folder.mkdir()
        channel_id = str(uuid.uuid4())
        listener = ingress if not results else port()
        result = {'case': case['name'], 'channelId': channel_id, 'status': 'failed'}
        saved = False
        try:
            model = channel(case, channel_id, listener, profile['id'])
            (folder / 'channel-submitted.xml').write_text(model)
            write(folder / 'case.json', case)
            value = api.call('/channels/' + channel_id, 'PUT', model, 'application/xml')
            assert value is True or isinstance(value, dict) and value.get('boolean') is True, value
            saved = True
            write(folder / 'channel-readback.json', api.call('/channels/' + channel_id))
            write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
            transport = Api(f'http://127.0.0.1:{listener}', lambda: require_listener(child, listener))
            for index in range(case.get('repetitions', 1)):
                response = transport.call('/python/', 'POST', case['payload'], 'text/plain')
                write(folder / ('response.json' if index == 0 else f'response-{index + 1}.json'), response)
                verify(case, response)
            write(folder / 'messages.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
            result['status'] = 'passed'
        except Exception as failure:
            result['error'] = str(failure)
        finally:
            if saved:
                try:
                    write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
                except Exception as failure:
                    result.update(status='failed', cleanupError=str(failure))
            results.append(result)
            write(evidence / 'cases.json', results)
            print(case['name'] + ': ' + result['status'], flush=True)
    receipt['assertions'] = [item['case'] for item in results if item['status'] == 'passed']
    failures = [item for item in results if item['status'] != 'passed']
    assert not failures, failures
    receipt['status'] = 'passed'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=[case['name'] for case in CASES], action='append')
    args = parser.parse_args()
    run(args, lambda *values: scenario(*values, selected=args.case))
