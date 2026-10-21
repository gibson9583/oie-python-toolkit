#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Validate primitive and stock boxed RAW arguments through all transformer phases."""
import argparse
from pathlib import Path
import copy
import json
import uuid
import xml.etree.ElementTree as ET
from live_engine_helpers import Api, javascript_step, require_listener, write
from run_code_template_isolated import make_template, make_channel, ROOT, run as run_isolated


def run(api, child, profile, ingress, evidence, receipt):
    template_id, library_id, channel_id = [str(uuid.uuid4()) for _ in range(3)]
    template = make_template(template_id, "return arguments[0] + '|OK'\n")
    write(evidence / 'raw-template-submitted.json', template)
    saved = api.call('/codeTemplates/' + template_id, 'PUT', template)
    assert saved is True or saved.get('boolean') is True, saved
    library = {'list': {'codeTemplateLibrary': [{'@version': '4.6.0', 'id': library_id,
        'name': 'Boxed raw message review', 'revision': 0, 'description': 'Owned review case',
        'includeNewChannels': False, 'enabledChannelIds': {'string': channel_id},
        'disabledChannelIds': '', 'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}}
    linked = api.call('/codeTemplateLibraries', 'PUT', library)
    assert linked is True or linked.get('boolean') is True, linked
    paths = {
        'source': 'sourceConnector/transformer/elements',
        'destination': 'destinationConnectors/connector/transformer/elements',
        'response': 'destinationConnectors/connector/responseTransformer/elements',
    }
    transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
    findings = []
    for phase, target in paths.items():
        for boxed in (False, True):
            case = phase + ('-boxed' if boxed else '-primitive')
            channel = ET.fromstring(make_channel(channel_id, ingress))
            for path in paths.values():
                channel.find(path).clear()
            argument = 'msg' if boxed else 'String(msg)'
            script = ("var rawKind = Object.prototype.toString.call(msg);\n"
                "try { msg = python_function(" + argument + "); } "
                "catch (failure) { msg = 'REJECTED|' + String(failure.message) + '|kind=' + rawKind; }\n")
            channel.find(target).append(javascript_step(script, case))
            xml = ET.tostring(channel, encoding='unicode')
            (evidence / (case + '-channel.xml')).write_text(xml)
            result = api.call('/channels/' + channel_id + '?override=true', 'PUT', xml, 'application/xml')
            assert result is True or result.get('boolean') is True, result
            write(evidence / (case + '-deploy.json'), api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
            response = transport.call('/python/', 'POST', 'hello', 'text/plain')
            write(evidence / (case + '-response.json'), response)
            record = {'case': case, 'response': response}
            findings.append(record)
            assert response == 'hello|OK', record
    write(evidence / 'boxed-raw-results.json', findings)
    write(evidence / 'messages-final.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
    write(evidence / 'template-final.json', api.call('/codeTemplates/' + template_id))
    write(evidence / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    receipt.update(status='passed', channelId=channel_id, templateId=template_id, libraryId=library_id,
        assertions=['primitive-raw-function-call-passes-all-phases', 'stock-boxed-raw-function-call-passes-all-phases'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run_isolated(parser.parse_args(), run)
