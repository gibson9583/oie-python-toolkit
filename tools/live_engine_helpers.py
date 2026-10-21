#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Read-only API helpers and owned channel models for live plugin validation."""
import copy, hashlib, http.cookiejar, json, socket, subprocess, urllib.error, urllib.request, xml.etree.ElementTree as ET
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parent

def write(path, value): path.write_text(json.dumps(value, indent=2) + '\n')
def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()
def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); return sock.getsockname()[1]

def decode(text):
    try:
        value = json.loads(text)
        if isinstance(value, dict) and set(value) == {'string'}:
            try: return json.loads(value['string'])
            except (ValueError, TypeError): return value['string']
        return value
    except ValueError: return text

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl): return None

class Api:
    def __init__(self, base, guard):
        self.base, self.guard = base, guard
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def call(self, path, method='GET', body=None, content='application/json', status=(200, 204), accept='application/json'):
        self.guard()
        raw = json.dumps(body) if body is not None and not isinstance(body, str) else body
        request = urllib.request.Request(self.base + path, data=raw.encode() if raw is not None else None, method=method,
            headers={'Content-Type': content, 'Accept': accept, 'X-Requested-With': 'PythonToolkitQualification'})
        try:
            with self.opener.open(request, timeout=75) as response: code, text = response.status, response.read().decode()
        except urllib.error.HTTPError as failure: code, text = failure.code, failure.read().decode()
        if code not in status: raise AssertionError(f'{method} {path}: {code}: {text[:1800]}')
        return decode(text)

def require_listener(child, listener):
    if child.poll() is not None: raise ConnectionError('Expected live engine exited')
    result = subprocess.run(['lsof', '-nP', '-a', '-p', str(child.pid), f'-iTCP:{listener}', '-sTCP:LISTEN', '-Fp'], capture_output=True, text=True, timeout=5)
    if result.returncode or {x[1:] for x in result.stdout.splitlines() if x.startswith('p')} != {str(child.pid)}:
        raise ConnectionError(f'Expected live engine does not own listener {listener}; refusing request')

def element(parent, name, text=None, **attrs):
    item = ET.SubElement(parent, name, attrs); item.text = text; return item

def script_fields(parent, profile, source, timeout=30000):
    element(parent, 'pythonScript', source); element(parent, 'environmentProfileId', profile)
    element(parent, 'environmentNameHint', 'Qualification'); element(parent, 'timeoutMillis', str(timeout))
    element(parent, 'contractVersion', '1')

def step(profile, source, name):
    result = ET.Element('com.mirth.connect.plugins.python.PythonStep', version='4.6.0')
    element(result, 'name', name); element(result, 'sequenceNumber', '0'); element(result, 'enabled', 'true')
    script_fields(result, profile, source); return result

def javascript_step(source, name):
    result=ET.Element('com.mirth.connect.plugins.javascriptstep.JavaScriptStep',version='4.6.0')
    element(result,'name',name); element(result,'sequenceNumber','0'); element(result,'enabled','true'); element(result,'script',source)
    return result

def channel_xml(cid, ingress, profile, marker='A', reader=False, marshmallow=False, writer_source=None, timeout=30000):
    root = ET.parse(TOOLS / 'integration-channel.xml').getroot()
    root.find('id').text = cid; root.find('name').text = f'Python qualification {marker}'; root.find('revision').text = '0'
    src = root.find('sourceConnector'); src.find('properties/listenerConnectorProperties/port').text = str(ingress)
    src.find('properties/contextPath').text = '/python'; src.find('properties/responseContentType').text = 'text/plain'
    dst = root.find('destinationConnectors/connector'); old = dst.find('properties'); old_defaults = copy.deepcopy(old.find('destinationConnectorProperties'))
    dst.remove(old); properties = ET.Element('properties', {'class': 'com.mirth.connect.connectors.python.PythonDispatcherProperties', 'version': '4.6.0'})
    properties.append(old_defaults)
    writer_source = writer_source or f"connectorMap['writer'] = '{marker}'\nreturn Response(data='{marker}|' + payload)\n"
    script_fields(properties, profile, writer_source, timeout); dst.insert(2, properties); dst.find('transportName').text = 'Python Writer'
    source_code = "msg = msg + '|source'\nchannelMap['source'] = 'python'\n"
    if marshmallow:
        source_code = "from marshmallow import Schema, fields\nimport json\nclass Patient(Schema):\n    patient_id = fields.String(required=True)\npatient = Patient().load(json.loads(msg))\nmsg = patient['patient_id'] + '|marshmallow'\n"
    source_elements = src.find('transformer/elements'); source_elements.clear(); source_elements.append(javascript_step("msg = msg + '|js-before';",'Before Python')); source_elements.append(step(profile, source_code, 'Python source')); source_elements.append(javascript_step("msg = msg + '|js-after';",'After Python'))
    if marshmallow: source_elements.remove(source_elements[0])
    dst_elements = dst.find('transformer/elements'); dst_elements.clear(); dst_elements.append(step(profile, "msg = msg + '|destination'\n", 'Python destination'))
    response_elements = dst.find('responseTransformer/elements'); response_elements.clear(); response_elements.append(step(profile, "msg = msg + '|response'\nresponse.status_message = 'Python response'\n", 'Python response'))
    if reader:
        src_properties = src.find('properties'); source_defaults = copy.deepcopy(src_properties.find('sourceConnectorProperties')); src.remove(src_properties)
        properties = ET.Element('properties', {'class': 'com.mirth.connect.connectors.python.PythonReceiverProperties', 'version': '4.6.0'}); properties.append(source_defaults)
        poll = element(properties, 'pollConnectorProperties', version='4.6.0')
        for key, value in {'pollingType': 'INTERVAL', 'pollOnStart': 'true', 'pollingFrequency': '60000', 'pollingHour': '0', 'pollingMinute': '0'}.items(): element(poll, key, value)
        element(poll, 'cronJobs'); advanced = element(poll, 'pollConnectorPropertiesAdvanced'); element(advanced, 'weekly', 'true'); inactive = element(advanced, 'inactiveDays')
        for _ in range(8): element(inactive, 'boolean', 'false')
        for key, value in {'dayOfMonth':'1','allDay':'true','startingHour':'8','startingMinute':'0','endingHour':'17','endingMinute':'0'}.items(): element(advanced, key, value)
        code = "return [Message('', source_map={'origin':'reader'}, destination_ids=[]), Message(b'\\x00\\xff', destination_ids=[1])]\n"
        script_fields(properties, profile, code); src.insert(2, properties); src.find('transportName').text = 'Python Reader'
        source_elements.clear() # The Reader binary payload crosses the engine's base64 boundary.
    return ET.tostring(root, encoding='unicode')

