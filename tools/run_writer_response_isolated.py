#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Qualify Writer responses and native retry/queue handling on a private engine.

The shared isolated runner owns installation, database, process verification and
cleanup. This callback only creates channels in that owned engine.
"""
import argparse
from pathlib import Path
import shutil
import time
import uuid
import xml.etree.ElementTree as ET

from live_engine_helpers import Api, channel_xml, element, require_listener, write
from run_code_template_isolated import ROOT, run as run_isolated


def cases():
    yield dict(name='response-fields', source="return Response(Status.SENT, 'ack', 'brief', 'details')", status='SENT', data='ack', brief='brief', error='details')
    yield dict(name='response-error', source="return Response('ERROR', 'negative-ack', 'failed', 'remote failure')", status='ERROR', data='negative-ack', brief='failed', error='remote failure')
    yield dict(name='literal-error', source="return 'ERROR'", status='SENT', data='ERROR', brief='', error='')
    yield dict(name='none', source='return None', status='SENT', data='', brief='', error='')
    for status in ('SENT', 'ERROR'):
        yield dict(name='status-' + status.lower(), source='return Status.' + status, status=status, data='', brief='', error='')
    yield dict(name='queue-disabled', source='return Status.QUEUED', status='ERROR', data='', brief="Python returned QUEUED, but this destination's queue is disabled.", error="Python returned QUEUED, but this destination's queue is disabled.")
    yield dict(name='invalid-status', source="connectorMap['mustNotApply'] = 'changed'\nreturn Response('FILTERED')", status='ERROR', data='', diagnostic='Response.status', rollback=True)
    yield dict(name='exception', source="raise RuntimeError('writer-response-test')", status='ERROR', data='', diagnostic='writer-response-test')
    retry = "attempt = connectorMap.get('attempt', 0) + 1\nconnectorMap['attempt'] = attempt\nreturn Response(Status.%s if attempt == 1 else Status.SENT, str(attempt), 'attempt-' + str(attempt))"
    yield dict(name='immediate-retry', source=retry % 'ERROR', retries=1, status='SENT', data='2', brief='attempt-2', error='', attempts=2)
    yield dict(name='retry-exhaustion', source="attempt = connectorMap.get('attempt', 0) + 1\nconnectorMap['attempt'] = attempt\nreturn Response(Status.ERROR, str(attempt), 'exhausted', 'remote failure')", retries=2, status='ERROR', data='3', brief='exhausted', error='remote failure', attempts=3)
    yield dict(name='queued-retry', source=retry % 'QUEUED', queue=True, retry_interval=5000, intermediate_queued=True, status='SENT', data='2', brief='attempt-2', error='', attempts=2)
    yield dict(name='queue-first', source="return Response(Status.SENT, 'queued-delivery', 'complete')", queue=True, send_first=False, status='SENT', data='queued-delivery', brief='complete', error='')
    # The stock HL7 validator consumes real Writer return data. These are valid
    # ER7 ACK/NACK messages, not fabricated engine/API responses.
    ack = 'MSH|^~\\&|REMOTE|LAB|OIE|LAB|20261021000000||ACK|ACK1|P|2.5\rMSA|AA|CONTROL1\r'
    nack = ack.replace('MSA|AA|CONTROL1', 'MSA|AE|CONTROL1|Remote rejection')
    yield dict(name='validation-ack', source='return Response(Status.SENT, %r, "accepted")' % ack,
               hl7=True, validate=True, status='SENT', data=ack, brief='accepted', error='')
    yield dict(name='validation-nack', source='return Response(Status.SENT, %r)' % nack,
               hl7=True, validate=True, status='ERROR', data=nack, diagnostic='NACK sent from receiver. (Error)\nRemote rejection')
    yield dict(name='validation-disabled-nack', source='return Response(Status.SENT, %r)' % nack,
               hl7=True, validate=False, status='SENT', data=nack, brief='', error='')
    yield dict(name='validation-status-sent', source='return Status.SENT', hl7=True, validate=True,
               status='ERROR', data='', diagnostic='Empty or blank response received.')
    yield dict(name='validation-status-error', source='return Status.ERROR', hl7=True, validate=True,
               status='ERROR', data='', brief='', error='')


def configure_hl7_response(model):
    """Use the shipped HL7 validator with control-ID matching deliberately off.

    The outgoing test payload is RAW; this tests ACK status interpretation,
    rather than outbound HL7 serialization or message-control-ID correlation.
    """
    transformer = model.find('destinationConnectors/connector/responseTransformer')
    for direction in ('inbound', 'outbound'):
        transformer.find(direction + 'DataType').text = 'HL7V2'
        properties = transformer.find(direction + 'Properties')
        properties.clear()
        properties.attrib.update({'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DataTypeProperties', 'version': '4.6.0'})
        serialization = element(properties, 'serializationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2SerializationProperties', 'version': '4.6.0'})
        for key, value in {'handleRepetitions': 'true', 'handleSubcomponents': 'true', 'useStrictParser': 'false',
                           'useStrictValidation': 'false', 'stripNamespaces': 'false', 'segmentDelimiter': r'\r', 'convertLineBreaks': 'true'}.items():
            element(serialization, key, value)
        deserialization = element(properties, 'deserializationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2DeserializationProperties', 'version': '4.6.0'})
        for key, value in {'useStrictParser': 'false', 'useStrictValidation': 'false', 'segmentDelimiter': r'\r'}.items():
            element(deserialization, key, value)
        batch = element(properties, 'batchProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2BatchProperties', 'version': '4.6.0'})
        element(batch, 'splitType', 'MSH_Segment')
        element(batch, 'batchScript', '')
        generation = element(properties, 'responseGenerationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2ResponseGenerationProperties', 'version': '4.6.0'})
        for key, value in {'segmentDelimiter': r'\r', 'successfulACKCode': 'AA', 'successfulACKMessage': '',
                           'errorACKCode': 'AE', 'errorACKMessage': 'An Error Occurred Processing Message.',
                           'rejectedACKCode': 'AR', 'rejectedACKMessage': 'Message Rejected.', 'msh15ACKAccept': 'false',
                           'dateFormat': 'yyyyMMddHHmmss.SSS'}.items():
            element(generation, key, value)
        validation = element(properties, 'responseValidationProperties', **{'class': 'com.mirth.connect.plugins.datatypes.hl7v2.HL7v2ResponseValidationProperties', 'version': '4.6.0'})
        for key, value in {'successfulACKCode': 'AA,CA', 'errorACKCode': 'AE,CE', 'rejectedACKCode': 'AR,CR',
                           'validateMessageControlId': 'false', 'originalMessageControlId': 'Destination_Encoded', 'originalIdMapVariable': ''}.items():
            element(validation, key, value)


def destination_message(value):
    messages = value.get('list', {}).get('message', [])
    if isinstance(messages, dict):
        messages = [messages]
    if not messages:
        return None
    entries = messages[0]['connectorMessages'].get('entry', [])
    if isinstance(entries, dict):
        entries = [entries]
    return next((entry['connectorMessage'] for entry in entries if int(entry['int']) == 1), None)


def scenario(api, child, profile, ingress, evidence, receipt):
    shutil.copyfile(__file__, evidence / Path(__file__).name)
    results = []
    for case in cases():
        folder = evidence / case['name']
        folder.mkdir()
        channel_id = str(uuid.uuid4())
        source = 'from oie_python import Response, Status\n' + case['source'] + '\n'
        queued_release = folder / 'release-queued-retry' if case.get('intermediate_queued') else None
        if queued_release is not None:
            # The first queue send can happen immediately after the initial
            # send. Hold its completion until the persisted first response is
            # observed, rather than racing a transient database state.
            gate = ("if attempt > 1:\n"
                    "    import time\n"
                    "    from pathlib import Path\n"
                    "    deadline = time.monotonic() + 20\n"
                    "    while not Path(" + repr(str(queued_release)) + ").exists():\n"
                    "        if time.monotonic() >= deadline:\n"
                    "            raise RuntimeError('Owned queued-response observation timed out')\n"
                    "        time.sleep(0.02)\n")
            source = source.replace('return Response(', gate + 'return Response(', 1)
        model = ET.fromstring(channel_xml(channel_id, ingress, profile['id'], marker=case['name'], writer_source=source))
        model.find('name').text = 'Py Writer ' + case['name']
        for path in ('sourceConnector/transformer/elements', 'destinationConnectors/connector/transformer/elements', 'destinationConnectors/connector/responseTransformer/elements'):
            model.find(path).clear()
        settings = model.find('destinationConnectors/connector/properties/destinationConnectorProperties')
        settings.find('queueEnabled').text = str(case.get('queue', False)).lower()
        settings.find('sendFirst').text = str(case.get('send_first', True)).lower()
        settings.find('retryCount').text = str(case.get('retries', 0))
        settings.find('retryIntervalMillis').text = str(case.get('retry_interval', 100))
        settings.find('validateResponse').text = str(case.get('validate', False)).lower()
        if case.get('hl7'):
            configure_hl7_response(model)
        body = ET.tostring(model, encoding='unicode')
        (folder / 'channel.xml').write_text(body)
        saved = api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
        assert saved is True or isinstance(saved, dict) and saved.get('boolean') is True, saved
        try:
            write(folder / 'deploy.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
            transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
            write(folder / 'http-response.json', transport.call('/python/', 'POST', 'outgoing', 'text/plain'))
            deadline = time.monotonic() + 30
            queued_observed = False
            while True:
                messages = api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100')
                write(folder / 'messages.json', messages)
                destination = destination_message(messages)
                if case.get('intermediate_queued') and destination is not None and destination['status'] == 'QUEUED' and destination.get('sendAttempts') == 1:
                    write(folder / 'intermediate-queued-messages.json', messages)
                    queued_response = ET.fromstring(destination['response']['content'])
                    queued_fields = {key: queued_response.findtext(key, '') for key in ('status', 'message', 'statusMessage', 'error')}
                    write(folder / 'intermediate-queued-response.json', queued_fields)
                    assert queued_fields == {'status': 'QUEUED', 'message': '1', 'statusMessage': 'attempt-1', 'error': ''}, queued_fields
                    queued_observed = True
                    queued_release.write_text('Persisted QUEUED response verified\n')
                if destination is not None and destination['status'] == case['status'] and destination.get('sendAttempts', 0) == case.get('attempts', 1):
                    break
                assert time.monotonic() < deadline, (case, destination)
                time.sleep(0.2)
            assert not case.get('intermediate_queued') or queued_observed, 'Missed the persisted QUEUED state before retry'
            response = ET.fromstring(destination['response']['content'])
            observed = {key: response.findtext(key, '') for key in ('status', 'message', 'statusMessage', 'error')}
            write(folder / 'response-decoded.json', observed)
            assert observed['status'] == case['status'], (case, observed)
            assert observed['message'] == case['data'], (case, observed)
            if 'diagnostic' in case:
                assert case['diagnostic'] in observed['error'], (case, observed)
            else:
                assert observed['statusMessage'] == case['brief'] and observed['error'] == case['error'], (case, observed)
            if case.get('rollback'):
                assert 'mustNotApply' not in str(destination['connectorMapContent']), destination
            results.append({'case': case['name'], 'channelId': channel_id, 'response': observed, 'sendAttempts': destination['sendAttempts'],
                            'validationEnabled': case.get('validate', False), 'intermediateQueuedObserved': queued_observed})
            write(evidence / 'writer-results.json', results)
        finally:
            if queued_release is not None:
                queued_release.write_text('Release owned retry before undeploy\n')
            write(folder / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
    receipt.update(status='passed', assertions=[item['case'] for item in results])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run_isolated(parser.parse_args(), scenario)
