#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify flat scripts and stock logging using the shared owned-engine lifecycle."""
import argparse
from pathlib import Path
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import Api, channel_xml, port, require_listener, write
from run_code_template_isolated import ROOT, run
from run_xml_roundtrip_isolated import PHASES, scenario as xml_scenario, xml_case


def logs(marker):
    return '\n'.join(f"logger.{level}('{marker}-{level}')" for level in ['info', 'warning', 'error', 'debug']) + f"\nprint('{marker}-print')\n"


def data_case(phase, datatype):
    name = datatype.lower() + '-' + phase
    case = dict(name=name, phase=phase, datatype=datatype, repetitions=2)
    if datatype == 'JSON':
        case.update(payload='{"trace":["input"]}', template='{"trace":["template"]}',
            before="msg.trace.push('js-before'); tmp.trace.push('js-before');",
            python="msg['trace'].append('python')\ntmp['trace'].append('python')\n",
            after="if (msg.trace.join('|') !== 'input|js-before|python' || tmp.trace.join('|') !== 'template|js-before|python') throw 'Lost JSON edits'; msg.trace.push('js-after'); tmp.trace.push('js-after');",
            expectedResponse={'trace': ['template', 'js-before', 'python', 'js-after']})
    else:
        case.update(payload='input', template='template', before="msg += '|js-before'; tmp += '|js-before';",
            python="msg += '|python'\ntmp += '|python'\n",
            after="if (String(msg) !== 'input|js-before|python' || String(tmp) !== 'template|js-before|python') throw 'Lost raw edits'; msg += '|js-after'; tmp += '|js-after';",
            expectedResponse='template|js-before|python|js-after')
    return case


def rollback_case(phase):
    case = xml_case(phase)
    case['name'] = 'rollback-' + phase
    # Stock JavaScriptBuilder concatenates the steps into one transformer function.
    # Catch the wrapper failure in the next JS step to inspect the original values.
    case['before'] += "\nvar originalMsg=msg, originalTmp=tmp; var beforeMsg=msg.toXMLString(), beforeTmp=tmp.toXMLString(); channelMap.put('keep','before'); try {"
    case['python'] = "msg = '<changed>valid</changed>'\ntmp = '<broken>'\nchannelMap['keep'] = 'partial'\n"
    case['after'] = """throw 'Expected XML rejection'; } catch (failure) {
if (String(failure).indexOf('tmp is not well-formed XML') < 0) throw failure;
}
if (msg !== originalMsg || tmp !== originalTmp || msg.toXMLString() !== beforeMsg || tmp.toXMLString() !== beforeTmp || String(channelMap.get('keep')) !== 'before') throw 'Partial changes escaped failed Python';
tmp = new XML('<result>rollback-preserved</result>');
"""
    case['expectedResponse'] = '<result>rollback-preserved</result>'
    return case


CASES = ([xml_case(phase, namespace='urn:r') for phase in PHASES]
    + [data_case(phase, datatype) for datatype in ['JSON', 'RAW'] for phase in PHASES]
    + [rollback_case(phase) for phase in PHASES])
for case in CASES:
    if case['name'].startswith('rollback-'):
        continue
    case['repetitions'] = 2
    case['python'] = """assert 'ctx' not in globals() and 'output' not in globals()
assert 'helper_state' not in globals()
helper_state = []
def transform(unused, another):
    raise AssertionError('Helpers must not run implicitly')
def helper():
    helper_state.append('called')
helper()
assert helper_state == ['called']
""" + case['python'] + logs(case['name'])


def connector_case(api, child, profile, evidence, reader):
    name = 'flat-reader' if reader else 'flat-writer'
    folder = evidence / name
    folder.mkdir()
    channel_id, listener = str(uuid.uuid4()), port()
    model = ET.fromstring(channel_xml(channel_id, listener, profile['id'], marker=name, reader=reader))
    for path in PHASES.values():
        model.find(path + '/elements').clear()
    source = logs(name) + ("assert 'poll' not in globals()\nassert isinstance(sourceMap['pollId'], str)\n" if reader else "from oie_python import Response\nreturn Response(data='writer|' + payload)\n")
    properties = model.find('sourceConnector/properties' if reader else 'destinationConnectors/connector/properties')
    properties.find('pythonScript').text = source
    (folder / 'channel-submitted.xml').write_text(ET.tostring(model, encoding='unicode'))
    saved = False
    try:
        result = api.call('/channels/' + channel_id, 'PUT', ET.tostring(model, encoding='unicode'), 'application/xml')
        assert result is True or isinstance(result, dict) and result.get('boolean') is True, result
        saved = True
        write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        if reader:
            deadline = time.monotonic() + 30
            while name + '-print' not in engine_log(evidence) and time.monotonic() < deadline:
                time.sleep(.2)
            assert name + '-print' in engine_log(evidence), 'Reader did not complete its poll'
            api.call('/channels/' + channel_id + '/_stop?returnErrors=true', 'POST')
            count = api.call('/channels/' + channel_id + '/messages/maxMessageId')
            write(folder / 'max-message-id.json', count)
            assert (count.get('long', 0) if isinstance(count, dict) else count) == 0, count
        else:
            transport = Api(f'http://127.0.0.1:{listener}', lambda: require_listener(child, listener))
            response = transport.call('/python/', 'POST', 'input', 'text/plain')
            write(folder / 'response.json', response)
            assert response == 'writer|input', response
        write(folder / 'messages.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    finally:
        if saved:
            write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    return name


def engine_log(evidence):
    return (evidence / 'owned-runtime/oie/logs/mirth.log').read_text()


def scenario(api, child, profile, ingress, evidence, receipt):
    xml_scenario(api, child, profile, ingress, evidence, receipt, cases=CASES)
    shutil.copyfile(__file__, evidence / 'flat-scenario.py')
    for reader in [False, True]:
        receipt['assertions'].append(connector_case(api, child, profile, evidence, reader))
    expected = [(case['name'], 'response' if case['phase'] == 'response' else 'transformer', 2)
        for case in CASES if not case['name'].startswith('rollback-')]
    expected += [('flat-writer', 'js-connector', 1), ('flat-reader', 'js-connector', 1)]
    lines = engine_log(evidence).splitlines()
    matches = []
    for marker, category, count in expected:
        for method, level in [('info', 'INFO'), ('warning', 'WARN'), ('error', 'ERROR'), ('debug', 'DEBUG'), ('print', 'INFO')]:
            found = [line for line in lines if line.endswith('] ' + marker + '-' + method)]
            assert len(found) == count, (marker, method, found)
            assert all(line.startswith(level + ' ') and f'] {category}: [Python ' in line and ' / ' in line for line in found), found
            matches.extend(found)
    write(evidence / 'script-logs.json', matches)
    receipt['assertions'].append('stock-log-level-category-and-channel-label')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args(), scenario)
