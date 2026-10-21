#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Prove callable templates on an owned stock engine and fresh Derby database.

Never accesses the reference engine. Preserve evidence and deduplicated package
inputs; remove only the marked installation/database after owned processes exit.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tarfile
import tempfile
import time
import traceback
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
import zipfile

from live_engine_helpers import Api, element, javascript_step, port, require_listener, sha, write
from inspect_plugin import inspect as inspect_plugin

ROOT = Path(__file__).resolve().parents[1]
CONTEXTS = ['SOURCE_FILTER_TRANSFORMER', 'DESTINATION_FILTER_TRANSFORMER', 'DESTINATION_RESPONSE_TRANSFORMER']
PYTHON = '/extensions/python-runtime'
PROPERTIES = 'com.mirth.connect.plugins.python.PythonCodeTemplateProperties'


def pin_plugin(path, package_bytes):
    """Publish a complete immutable pin, accepting only identical concurrent pins."""
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation of the final file exposes partial writes to other runs.
    # A hard link publishes the finished sibling atomically without overwriting.
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.pin-', delete=False) as pinned:
        temporary = Path(pinned.name)
        try:
            pinned.write(package_bytes)
            pinned.flush()
            os.fsync(pinned.fileno())
        except BaseException:
            temporary.unlink()
            raise
    try:
        try:
            os.link(temporary, path)
        except FileExistsError:
            pass
        actual = path.read_bytes()
        assert actual == package_bytes and hashlib.sha256(actual).digest() == hashlib.sha256(package_bytes).digest(), 'Pinned plugin content mismatch'
    finally:
        temporary.unlink()


def configure(home, runtime, http, https, password):
    path = home / 'conf/mirth.properties'
    text = path.read_text()
    database = f'jdbc:derby:{runtime / "appdata/mirthdb"};create=true'
    values = {'dir.appdata': str(runtime / 'appdata'), 'database': 'derby', 'database.url': database,
        'database.username': '', 'database.password': '', 'database-readonly.url': database,
        'database-readonly.username': '', 'database-readonly.password': '',
        'database.enable-read-write-split': 'false', 'http.host': '127.0.0.1', 'https.host': '127.0.0.1',
        'http.port': str(http), 'https.port': str(https), 'server.api.allowhttp': 'true', 'server.initialadminpassword': password}
    for key, value in values.items():
        pattern = r'(?m)^\s*' + re.escape(key) + r'\s*=.*$'
        replacement = key + ' = ' + value
        text = re.sub(pattern, lambda _: replacement, text) if re.search(pattern, text) else text + '\n' + replacement + '\n'
    path.write_text(text)
    path.chmod(0o600)
    (home / 'conf/custom.vmoptions').write_text('# Owned Python template qualification\n')


def process_snapshot():
    result = subprocess.run(['ps', '-axo', 'pid=,ppid=,lstart=,command='], capture_output=True, text=True, check=True)
    out = {}
    for line in result.stdout.splitlines():
        match = re.match(r'\s*(\d+)\s+(\d+)\s+(.{24})\s+(.*)', line)
        if match:
            pid, parent, birth, command = match.groups()
            out[int(pid)] = {'pid': int(pid), 'parent': int(parent), 'birth': birth, 'command': command}
    return out


def descendants(pid, snapshot):
    owned = {pid}
    while True:
        before = len(owned)
        owned.update(record['pid'] for record in snapshot.values() if record['parent'] in owned)
        if len(owned) == before:
            return [snapshot[item] for item in sorted(owned) if item in snapshot]


def make_template(template_id, source):
    return {'codeTemplate': {'@version': '4.6.0', 'id': template_id, 'name': 'Python callable qualification', 'revision': 0,
        'contextSet': {'delegate': {'contextType': CONTEXTS}}, 'properties': {'@class': PROPERTIES, '@version': '4.6.0',
        'type': 'FUNCTION', 'functionName': 'python_function', 'pythonScript': source,
        'environmentProfileId': '232030e4-aa0d-39fc-a889-fed1318ef412', 'environmentNameHint': 'Vanilla Python (standard library)',
        'timeoutMillis': 30000, 'contractVersion': 1}}}


def make_channel(channel_id, ingress):
    model = ET.parse(ROOT / 'tools/integration-channel.xml').getroot()
    model.find('id').text = channel_id
    model.find('name').text = 'Python callable qualification'
    model.find('sourceConnector/properties/listenerConnectorProperties/port').text = str(ingress)
    model.find('sourceConnector/properties/contextPath').text = '/python'
    model.find('sourceConnector/properties/responseContentType').text = 'text/plain'
    destination = model.find('destinationConnectors/connector')
    old = destination.find('properties')
    defaults = copy.deepcopy(old.find('destinationConnectorProperties'))
    destination.remove(old)
    properties = ET.Element('properties', {'class': 'com.mirth.connect.connectors.js.JavaScriptDispatcherProperties', 'version': '4.6.0'})
    properties.append(defaults)
    element(properties, 'script', 'return connectorMessage.getEncodedData();')
    destination.insert(2, properties)
    destination.find('transportName').text = 'JavaScript Writer'
    for path, phase in [('sourceConnector/transformer/elements', 'source'),
            ('destinationConnectors/connector/transformer/elements', 'destination'),
            ('destinationConnectors/connector/responseTransformer/elements', 'response')]:
        steps = model.find(path)
        steps.clear()
        steps.append(javascript_step(f"msg = python_function(String(msg), '{phase}');", phase))
    return ET.tostring(model, encoding='unicode')


def observe_database(args, home, runtime, evidence):
    source = evidence / 'ObserveDatabase.java'
    source.write_text('''import java.sql.*;
class ObserveDatabase {
 public static void main(String[] args) throws Exception {
  try (Connection db=DriverManager.getConnection("jdbc:derby:"+args[0]); Statement s=db.createStatement()) {
   for (String table : new String[]{"CHANNEL", "CODE_TEMPLATE", "CODE_TEMPLATE_LIBRARY"}) {
    try(ResultSet r=s.executeQuery("SELECT ID,NAME,REVISION FROM "+table+" ORDER BY ID")) {
     while(r.next()) System.out.println(table+"\\t"+r.getString(1)+"\\t"+r.getString(2)+"\\t"+r.getInt(3));
    }
   }
   try(ResultSet r=s.executeQuery("SELECT ID,CODE_TEMPLATE FROM CODE_TEMPLATE")) {
    while(r.next()) {
     String value=r.getString(2);
     if(!value.contains("PythonCodeTemplateProperties") || !value.contains("pythonScript")) throw new AssertionError("Stored Python template missing");
     String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     System.out.println("TEMPLATE_ROW\\t"+r.getString(1)+"\\tsha256="+hash+"\\tcustomClass=true\\tpythonSource=true");
    }
   }
   try(ResultSet r=s.executeQuery("SELECT CATEGORY,COUNT(*) FROM CONFIGURATION WHERE CATEGORY LIKE '%python%' GROUP BY CATEGORY")) {
    while(r.next()) System.out.println("CONFIGURATION\\t"+r.getString(1)+"\\t"+r.getLong(2));
   }
  }
  try {DriverManager.getConnection("jdbc:derby:;shutdown=true");} catch(SQLException e) {if(!"XJ015".equals(e.getSQLState()))throw e;}
 }
}
''')
    result = subprocess.run([str(args.java_home / 'bin/java'), '-cp', os.pathsep.join(map(str, home.rglob('derby*.jar'))),
        str(source), str(runtime / 'appdata/mirthdb')], cwd=evidence, capture_output=True, text=True, timeout=45)
    (evidence / 'database-final.txt').write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError('Final database observation failed')


def run(args, scenario=None):
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    evidence.chmod(0o700)
    runtime = evidence / 'owned-runtime'
    home = runtime / 'oie'
    owner = uuid.uuid4().hex
    child = None
    error = None
    receipt = {'owner': owner, 'runtime': str(runtime), 'status': 'starting', 'referenceEngineAccessed': False}
    artifacts = evidence.parent / 'artifacts' / 'sha256'
    package_bytes = args.plugin.read_bytes()
    digest = hashlib.sha256(package_bytes).hexdigest()
    package_pin = artifacts / digest / args.plugin.name
    pin_plugin(package_pin, package_bytes)
    write(evidence / 'inputs.json', {'engine': {'path': str(args.engine), 'sha256': sha(args.engine), 'sharedImmutableInput': True},
        'plugin': {'source': str(args.plugin), 'pinnedPath': str(package_pin), 'sha256': digest},
        'javaHome': str(args.java_home), 'python': str(args.python)})
    shutil.copyfile(__file__, evidence / 'runner.py')
    shutil.copyfile(ROOT / 'tools/live_engine_helpers.py', evidence / 'live_engine_helpers.py')
    shutil.copyfile(ROOT / 'tools/inspect_plugin.py', evidence / 'inspect_plugin.py')
    write(evidence / 'source-sha256.json', {str(path.relative_to(ROOT)): sha(path) for folder in ['shared/src', 'server/src', 'package/webadmin/web', 'python-sdk']
        for path in (ROOT / folder).rglob('*') if path.is_file() and path.suffix in ['.java', '.py', '.jsx', '.mjs', '.json', '.xml']})
    try:
        runtime.mkdir(mode=0o700)
        (runtime / '.owner').write_text(owner)
        with tarfile.open(args.engine) as archive:
            archive.extractall(runtime, filter='data')
        assert not (runtime / 'appdata').exists(), 'Expected a fresh database directory'
        assert not (home / 'extensions/python-runtime').exists(), 'Stock archive unexpectedly contains Python plugin'
        write(evidence / 'package-inspection.json', inspect_plugin(package_pin))
        with zipfile.ZipFile(package_pin) as archive:
            inventory = {}
            for item in archive.infolist():
                assert (home / 'extensions' / item.filename).resolve().is_relative_to((home / 'extensions').resolve())
                if item.is_dir():
                    continue
                data = archive.read(item)
                inventory[item.filename] = hashlib.sha256(data).hexdigest()
            archive.extractall(home / 'extensions')
        write(evidence / 'package-inventory.json', inventory)
        http, https, ingress = port(), port(), port()
        assert len({http, https, ingress}) == 3
        password = 'PythonTemplate-' + secrets.token_hex(20)
        configure(home, runtime, http, https, password)
        env = {key: value for key, value in os.environ.items() if key in ['PATH', 'HOME', 'USER', 'LOGNAME', 'LANG', 'TMPDIR']}
        env.update({'OIE_JAVA_PATH': str(args.java_home / 'bin/java'), 'INSTALL4J_JAVA_HOME_OVERRIDE': str(args.java_home),
            'OIE_PYTHON_RUNTIME_RUNTIMES': 'python3=' + str(args.python)})
        launcher = home / 'oieserver'
        launcher.chmod(0o700)
        with (evidence / 'engine-console.log').open('w') as output:
            child = subprocess.Popen([str(launcher)], cwd=home, env=env, stdout=output, stderr=subprocess.STDOUT)
        receipt.update(pid=child.pid, httpPort=http, httpsPort=https, ingressPort=ingress)
        write(evidence / 'process.json', receipt)
        api = Api(f'http://127.0.0.1:{http}/api', lambda: require_listener(child, http))
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise RuntimeError('Owned engine exited during startup')
            try:
                credentials = urllib.parse.urlencode({'username': 'admin', 'password': password})
                try:
                    login = api.call('/users/_login', 'POST', credentials, 'application/x-www-form-urlencoded')
                except AssertionError as failure:
                    if ': 401:' not in str(failure):
                        raise
                    api.call('/users/_login', 'POST', 'username=admin&password=admin', 'application/x-www-form-urlencoded')
                    api.call('/users/1/password', 'PUT', password, 'text/plain')
                    login = api.call('/users/_login', 'POST', credentials, 'application/x-www-form-urlencoded')
                if 'SUCCESS' in json.dumps(login):
                    break
            except (OSError, AssertionError):
                pass
            time.sleep(0.5)
        else:
            raise TimeoutError('Owned engine startup timed out')
        print('Owned stock engine authenticated', flush=True)
        write(evidence / 'engine-version.json', api.call('/server/version', accept='text/plain'))
        write(evidence / 'installed-plugins.json', api.call('/extensions/plugins'))
        info = api.call(PYTHON + '/runtime')
        write(evidence / 'runtime-info.json', info)
        assert info['runtimes'][0]['available'], info
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            listing = api.call(PYTHON + '/profiles')
            profile = next(item for item in listing['profiles'] if item['id'] == info['defaultProfileId'])
            if profile['readiness']['state'] == 'ready':
                break
            if profile['readiness']['state'] not in ['preparing', 'not-prepared']:
                raise AssertionError(profile)
            time.sleep(0.5)
        else:
            raise TimeoutError('Built-in environment did not prepare')
        write(evidence / 'profile-ready.json', profile)
        print('Built-in environment ready', flush=True)
        if scenario is not None:
            scenario(api, child, profile, ingress, evidence, receipt)
            return
        template_id, library_id, channel_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
        source_a = ("assert 'ctx' not in globals()\n"
            "print('flat-template-' + arguments[1] + '-print')\n"
            "logger.warning('flat-template-' + arguments[1] + '-warning')\n"
            "return arguments[0] + '|A-' + arguments[1]\n")
        model = make_template(template_id, source_a)
        write(evidence / 'template-submitted.json', model)
        saved = api.call('/codeTemplates/' + template_id, 'PUT', model)
        assert saved is True or saved.get('boolean') is True, saved
        readback = api.call('/codeTemplates/' + template_id)
        write(evidence / 'template-readback.json', readback)
        properties = readback['codeTemplate']['properties']
        assert properties['@class'] == PROPERTIES and properties['pythonScript'] == source_a and 'code' not in properties, properties
        referenced = api.call(PYTHON + '/profiles/' + profile['id'])
        write(evidence / 'profile-saved-template-reference.json', referenced)
        assert referenced['savedReferenceCount'] == 1 and referenced['deployedReferenceCount'] == 0, referenced
        # A custom unprepared environment needs no network or dependency work.
        # A saved, unlinked template alone must prevent archiving its profile.
        custom = api.call(PYTHON + '/profiles', 'POST', {'requestId': str(uuid.uuid4()),
            'expectedRegistryEpoch': profile['registryEpoch'], 'name': 'Unlinked template qualification',
            'runtimeId': info['runtimes'][0]['id'], 'packageSourceId': next(item['id'] for item in info['packageSources'] if item['kind'] != 'builtin'),
            'requirementsText': ''})['profile']
        unlinked_id = str(uuid.uuid4())
        unlinked = make_template(unlinked_id, source_a)
        unlinked['codeTemplate']['name'] = 'Unlinked Python qualification'
        unlinked['codeTemplate']['properties']['environmentProfileId'] = custom['id']
        saved_unlinked = api.call('/codeTemplates/' + unlinked_id, 'PUT', unlinked)
        assert saved_unlinked is True or saved_unlinked.get('boolean') is True, saved_unlinked
        archived = api.call(PYTHON + '/profiles/' + custom['id'] + '?' + urllib.parse.urlencode({
            'requestId': str(uuid.uuid4()), 'expectedRegistryEpoch': custom['registryEpoch'], 'expectedRevision': custom['revision']}),
            'DELETE', status=(409,))
        write(evidence / 'unlinked-template-archive-rejected.json', archived)
        assert archived['code'] == 'PROFILE_IN_USE' and '1 saved script(s) and 0 deployed channel(s)' in archived['message'], archived
        library = {'list': {'codeTemplateLibrary': [{'@version': '4.6.0', 'id': library_id, 'name': 'Python callable library', 'revision': 0,
            'description': 'Owned isolated qualification', 'includeNewChannels': False, 'enabledChannelIds': {'string': channel_id},
            'disabledChannelIds': '', 'codeTemplates': {'codeTemplate': [{'@version': '4.6.0', 'id': template_id}]}}]}}
        write(evidence / 'library-submitted.json', library)
        linked = api.call('/codeTemplateLibraries', 'PUT', library)
        assert linked is True or linked.get('boolean') is True, linked
        write(evidence / 'library-readback.json', api.call('/codeTemplateLibraries?includeCodeTemplates=true'))
        body = {**properties, 'requestId': str(uuid.uuid4()), 'role': 'code-template', 'contextTypes': CONTEXTS,
            'expectedNodeId': info['nodeId'], 'expectedRegistryEpoch': profile['registryEpoch'], 'expectedEnvironmentRevision': profile['revision']}
        body.pop('@class', None); body.pop('@version', None); body.pop('type', None)
        valid = api.call(PYTHON + '/templates/validate', 'POST', body)
        write(evidence / 'validation-valid.json', valid)
        assert valid['status'] == 'valid', valid
        spec = {'canonicalization': 1, 'contractVersion': 1, 'functionName': 'python_function',
            'kind': 'code-template', 'profileId': properties['environmentProfileId'], 'source': source_a, 'timeoutMillis': 30000}
        canonical = json.dumps(spec, sort_keys=True, separators=(',', ':'))
        (evidence / 'canonical-template-spec.json').write_text(canonical)
        assert valid['sourceDigest'] == hashlib.sha256(canonical.encode()).hexdigest(), valid
        assert valid['requestId'] == body['requestId'] and valid['nodeId'] == info['nodeId'], valid
        assert valid['registryEpoch'] == profile['registryEpoch'] and valid['environmentRevision'] == profile['revision'], valid
        reserved_results = {}
        for name in ['JSON', 'Array', 'Object', 'Packages', 'isFinite', 'String', 'Error', 'connectorMessage', 'responseStatus']:
            rejected = api.call(PYTHON + '/templates/validate', 'POST', {**body, 'functionName': name,
                'requestId': str(uuid.uuid4())}, status=(400,))
            assert rejected['code'] == 'INVALID_REQUEST', rejected
            reserved_results[name] = rejected
        write(evidence / 'validation-reserved-names.json', reserved_results)
        compile_only = {**body, 'requestId': str(uuid.uuid4()), 'pythonScript': "raise RuntimeError('MUST_NOT_EXECUTE')\nreturn None\n"}
        result = api.call(PYTHON + '/templates/validate', 'POST', compile_only)
        write(evidence / 'validation-compile-only.json', result)
        assert result['status'] == 'valid', result
        malformed = {**body, 'requestId': str(uuid.uuid4()), 'pythonScript': 'return (\n'}
        result = api.call(PYTHON + '/templates/validate', 'POST', malformed)
        write(evidence / 'validation-malformed.json', result)
        assert result['status'] == 'invalid', result
        channel = make_channel(channel_id, ingress)
        (evidence / 'channel-submitted.xml').write_text(channel)
        saved_channel = api.call('/channels/' + channel_id, 'PUT', channel, 'application/xml')
        assert saved_channel is True or saved_channel.get('boolean') is True, saved_channel
        write(evidence / 'channel-readback.json', api.call('/channels/' + channel_id))
        write(evidence / 'deploy-A.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        transport = Api(f'http://127.0.0.1:{ingress}', lambda: require_listener(child, ingress))
        first = transport.call('/python/', 'POST', 'hello', 'text/plain')
        write(evidence / 'response-A.json', first)
        assert first == 'hello|A-source|A-destination|A-response', first
        print('Python template invoked in all three transformer phases', flush=True)
        # Native template save compiles only its generated JavaScript wrapper.
        draft = copy.deepcopy(readback)
        draft['codeTemplate']['properties']['pythonScript'] = malformed['pythonScript']
        result = api.call('/codeTemplates/' + template_id + '?override=true', 'PUT', draft)
        write(evidence / 'malformed-draft-save.json', result)
        assert result is True or result.get('boolean') is True, result
        assert transport.call('/python/', 'POST', 'draft', 'text/plain') == 'draft|A-source|A-destination|A-response'
        updated = copy.deepcopy(readback)
        updated['codeTemplate']['properties']['pythonScript'] = source_a.replace('|A-', '|B-')
        result = api.call('/codeTemplates/' + template_id + '?override=true', 'PUT', updated)
        assert result is True or result.get('boolean') is True, result
        before = transport.call('/python/', 'POST', 'saved', 'text/plain')
        write(evidence / 'response-before-redeploy.json', before)
        assert before == 'saved|A-source|A-destination|A-response', before
        write(evidence / 'deploy-B.json', api.call('/channels/' + channel_id + '/_deploy?returnErrors=true', 'POST'))
        after = transport.call('/python/', 'POST', 'redeployed', 'text/plain')
        write(evidence / 'response-after-redeploy.json', after)
        assert after == 'redeployed|B-source|B-destination|B-response', after
        log_lines = (home / 'logs/mirth.log').read_text().splitlines()
        template_logs = []
        for phase in ['source', 'destination', 'response']:
            category = 'response' if phase == 'response' else 'transformer'
            for method, level in [('print', 'INFO'), ('warning', 'WARN')]:
                matches = [line for line in log_lines if line.endswith('] flat-template-' + phase + '-' + method)]
                assert len(matches) == 4, (phase, method, matches)
                assert all(line.startswith(level + ' ') and f'] {category}: [' in line for line in matches), matches
                template_logs.extend(matches)
        write(evidence / 'template-logs.json', template_logs)
        write(evidence / 'messages-final.json', api.call('/channels/' + channel_id + '/messages?includeContent=true&offset=0&limit=100'))
        write(evidence / 'template-final.json', api.call('/codeTemplates/' + template_id))
        write(evidence / 'profile-references.json', api.call(PYTHON + '/profiles/' + profile['id'] + '/references'))
        write(evidence / 'undeploy.json', api.call('/channels/' + channel_id + '/_undeploy', 'POST'))
        receipt.update(status='passed', channelId=channel_id, templateId=template_id, libraryId=library_id,
            assertions=['stock-plugin-startup', 'inspected-package-contract', 'json-custom-template-roundtrip', 'saved-template-count',
                'unlinked-template-prevents-profile-archive', 'library-channel-link',
                'compile-only-validation', 'malformed-python-validation', 'reserved-wrapper-name-rejection', 'template-only-channel-deployment',
                'source-destination-response-python-call', 'malformed-draft-save', 'saved-change-keeps-deployed-source',
                'redeploy-updates-source', 'template-print-and-logger-stock-routing'])
        print('Draft, validation, and redeployment checks passed', flush=True)
    except BaseException as failure:
        error = failure
        receipt.update(status='failed', error=str(failure))
        (evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        cleanup = {'owner': owner, 'runtime': str(runtime), 'status': 'blocked'}
        try:
            assert not runtime.is_symlink() and not (runtime / '.owner').is_symlink() and (runtime / '.owner').read_text() == owner, 'Ownership marker mismatch'
            cleanup['ownershipVerified'] = True
            owned = descendants(child.pid, process_snapshot()) if child else []
            write(evidence / 'processes-before-stop.json', owned)
            if child and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=15)
            receipt['exitCode'] = child.returncode if child else None
            cleanup['processExited'] = child is None or child.poll() is not None
            assert cleanup['processExited'], 'Owned engine still active'
            deadline = time.monotonic() + 15
            while True:
                now = process_snapshot()
                remaining = [record for record in owned if record['pid'] in now and record['birth'] == now[record['pid']]['birth']]
                if not remaining or time.monotonic() >= deadline:
                    break
                time.sleep(0.25)
            write(evidence / 'processes-after-stop.json', remaining)
            assert not remaining, 'Owned descendant processes remain; refusing cleanup'
            if (home / 'logs').exists():
                shutil.copytree(home / 'logs', evidence / 'engine-logs')
            if (runtime / 'appdata/mirthdb/service.properties').exists():
                observe_database(args, home, runtime, evidence)
            for path in (runtime / 'appdata/python-runtime').rglob('*.log'):
                target = evidence / 'runtime-logs' / path.relative_to(runtime / 'appdata/python-runtime')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            shutil.rmtree(runtime)
            cleanup.update(status='removed', runtimeAbsent=not runtime.exists(), databaseAbsent=not (runtime / 'appdata/mirthdb').exists())
        except BaseException as failure:
            cleanup['error'] = str(failure)
            if error is None:
                receipt.update(status='failed', error='Cleanup blocked: ' + str(failure))
                raise
        finally:
            write(evidence / 'process.json', receipt)
            write(evidence / 'cleanup.json', cleanup)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, default=ROOT / 'package/target/python-runtime-0.1.0.zip')
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3'))
    parser.add_argument('--evidence', type=Path, required=True)
    run(parser.parse_args())
