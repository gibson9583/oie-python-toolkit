#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Save/reopen Python connectors in the real, headful stock Swing Administrator.

Requires a graphical desktop and a JDK with JavaFX (the server JDK need not have
JavaFX). Reuses the isolated runner's ownership, final rows and cleanup contract.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import time
import uuid
import urllib.request
import xml.etree.ElementTree as ET

import run_code_template_isolated as isolated
from live_engine_helpers import channel_xml, sha, write
from release_contract import default_plugin

ROOT = Path(__file__).resolve().parents[1]


def connector_properties(document):
    channel = ET.fromstring(document)
    return [channel.find('sourceConnector/properties'),
            *channel.findall('destinationConnectors/connector/properties')]


def normalized(element):
    """Ignore XML formatting, retaining every attribute, field, and source byte."""
    return (element.tag, sorted(element.attrib.items()), (element.text or '') if not len(element) else '',
            [normalized(child) for child in element])


def assert_properties(before, after):
    expected = [normalized(item) for item in connector_properties(before)]
    actual = [normalized(item) for item in connector_properties(after)]
    assert actual == expected, 'Full connector property XML changed; compare retained before/after XML'


def client_classpath(home):
    # Match the shipped WebStart contract: core client JARs + CLIENT/SHARED
    # descriptor libraries. Never add server JARs or a neighboring source build.
    jars = set((home / 'client-lib').glob('*.jar'))
    for descriptor in (home / 'extensions').glob('*/*.xml'):
        metadata = ET.parse(descriptor).getroot()
        for library in metadata.findall('library'):
            if library.get('type') in ('SHARED', 'CLIENT'):
                jar = (descriptor.parent / library.get('path')).resolve()
                assert jar.is_relative_to(home.resolve()) and jar.is_file(), jar
                jars.add(jar)
    return sorted(jars)


def seed(api, profile, ingress, evidence):
    assert profile['name'] == 'Python', profile
    runtime = api.call('/extensions/oie-python-toolkit/runtime')
    sources = [item for item in runtime['packageSources'] if item['kind'] != 'builtin']
    catalog = api.call('/extensions/oie-python-toolkit/profiles')
    custom = api.call('/extensions/oie-python-toolkit/profiles', 'POST', {
        'requestId': str(uuid.uuid4()), 'expectedRegistryEpoch': catalog['registryEpoch'],
        'name': 'Swing preservation custom environment', 'runtimeId': runtime['runtimes'][0]['id'],
        'packageSourceId': sources[0]['id'], 'requirementsText': ''})['profile']
    profiles = [profile['id'], custom['id']]
    channels = []
    for index in range(2):
        channel_id = str(uuid.uuid4())
        channel = ET.fromstring(channel_xml(channel_id, ingress, profiles[index], reader=True))
        channel.find('name').text = 'Swing Python preservation ' + str(index)
        channel.find('nextMetaDataId').text = '3'
        # Native editors enrich cached channels from the separate metadata API.
        # Seed ordinary author/time metadata, rather than an absent timestamp
        # whose lazy initialization can look newer than the edit's start time.
        export = channel.find('exportData')
        if export is None:
            export = ET.SubElement(channel, 'exportData')
        metadata = export.find('metadata')
        if metadata is None:
            metadata = ET.SubElement(export, 'metadata')
            ET.SubElement(metadata, 'enabled').text = 'true'
        modified = ET.SubElement(metadata, 'lastModified')
        ET.SubElement(modified, 'time').text = str(int(time.time() * 1000) - 60_000)
        ET.SubElement(modified, 'timezone').text = 'UTC'
        ET.SubElement(metadata, 'userId').text = '1'
        source = channel.find('sourceConnector/properties')
        source.insert(0, ET.Element('pluginProperties'))
        source.find('pollConnectorProperties/pollOnStart').text = 'false'
        source.find('pollConnectorProperties/pollingFrequency').text = str(90123 + index)
        source.find('sourceConnectorProperties/processingThreads').text = str(2 + index)
        # Stock Swing replaces nonpositive queue buffer placeholders with the
        # server default. Use actual configured values to check preservation.
        source.find('sourceConnectorProperties/queueBufferSize').text = str(1201 + index)
        source.find('timeoutMillis').text = str(17001 + index)
        source.find('environmentNameHint').text = 'Reader environment café ' + str(index)
        source.find('pythonScript').text = '# Reader café — ' + str(index) + '\nvalue = "<&>"\nreturn Message(value)\n'
        destinations = channel.find('destinationConnectors')
        duplicate = copy.deepcopy(destinations[0])
        duplicate.find('metaDataId').text = '2'
        duplicate.find('name').text = 'Second Python Writer'
        destinations.append(duplicate)
        for row, connector in enumerate(destinations):
            properties = connector.find('properties')
            properties.insert(0, ET.Element('pluginProperties'))
            properties.find('pythonScript').text = ('# Writer ' + str(index) + '/' + str(row) + ' — 雪\n'
                'return Response(Status.SENT, "<&> café")\n')
            properties.find('environmentProfileId').text = profiles[(index + row + 1) % 2]
            properties.find('environmentNameHint').text = 'Writer environment ' + str(index) + '/' + str(row)
            properties.find('timeoutMillis').text = str(23001 + 100 * index + row)
            destination = properties.find('destinationConnectorProperties')
            destination.find('retryIntervalMillis').text = str(11001 + row)
            destination.find('retryCount').text = str(2 + row)
            destination.find('queueBufferSize').text = str(1301 + row)
        # Only the connector placeholder is in scope; no unsupported Swing Python
        # transformer/filter editor registrations are expected or manufactured.
        for elements in channel.findall('.//transformer/elements') + channel.findall('.//responseTransformer/elements'):
            elements.clear()
        body = ET.tostring(channel, encoding='unicode')
        (evidence / ('submitted-' + channel_id + '.xml')).write_text(body)
        result = api.call('/channels/' + channel_id, 'PUT', body, 'application/xml')
        assert result is True or result.get('boolean') is True, result
        baseline = api.call('/channels/' + channel_id, accept='application/xml')
        (evidence / ('before-' + channel_id + '.xml')).write_text(baseline)
        channels.append(channel_id)
    return channels


def observe_database(args, home, runtime, evidence):
    isolated_observe(args, home, runtime, evidence)
    source = evidence / 'ObserveSwingDatabase.java'
    source.write_text('''import java.sql.*;
import java.nio.file.*;
class ObserveSwingDatabase {
 public static void main(String[] args) throws Exception {
  try(Connection db=DriverManager.getConnection("jdbc:derby:"+args[0]); Statement s=db.createStatement();
      ResultSet r=s.executeQuery("SELECT ID,REVISION,CHANNEL FROM CHANNEL ORDER BY ID")) {
   while(r.next()) {
    String xml=r.getString(3);
    Files.writeString(Path.of(args[1],"database-channel-"+r.getString(1)+".xml"),xml);
    System.out.println(r.getString(1)+"\\t"+r.getInt(2)+"\\t"+
      java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
   }
  }
  try {DriverManager.getConnection("jdbc:derby:;shutdown=true");} catch(SQLException e) {if(!"XJ015".equals(e.getSQLState()))throw e;}
 }
}
''')
    result = subprocess.run([str(args.java_home / 'bin/java'), '-cp', os.pathsep.join(map(str, home.rglob('derby*.jar'))),
        str(source), str(runtime / 'appdata/mirthdb'), str(evidence)], cwd=evidence,
        capture_output=True, text=True, timeout=45)
    (evidence / 'database-channel-hashes.txt').write_text(result.stdout + result.stderr)
    assert result.returncode == 0, 'Final stored channel observation failed'
    for before in evidence.glob('before-*.xml'):
        channel_id = before.stem.removeprefix('before-')
        stored = (evidence / ('database-channel-' + channel_id + '.xml')).read_text()
        assert_properties(before.read_text(), stored)
        latest = evidence / ('verify-after-' + channel_id + '.xml')
        if latest.exists():
            observed, expected = ET.fromstring(stored), ET.fromstring(latest.read_text())
            assert observed.findtext('revision') == expected.findtext('revision'), 'Final database revision differs from live API'
            assert observed.findtext('description') == expected.findtext('description'), 'Final database native-save description differs from live API'


isolated_observe = isolated.observe_database


def scenario(args, api, child, profile, ingress, evidence, receipt):
    home = Path(receipt['runtime']) / 'oie'
    runtime = home.parent
    for name in ['firstlogin', 'checkForNotifications', 'showNotificationPopup']:
        api.call('/users/1/preferences/' + name, 'PUT', 'false', 'text/plain')
    channels = seed(api, profile, ingress, evidence)
    jars = client_classpath(home)
    write(evidence / 'client-classpath.json', {str(jar.relative_to(home)): sha(jar) for jar in jars})
    classes = runtime / 'swing-classes'
    classes.mkdir()
    source = ROOT / 'tools/SwingConnectorRoundtrip.java'
    shutil.copyfile(source, evidence / source.name)
    shutil.copyfile(__file__, evidence / Path(__file__).name)
    classpath = os.pathsep.join(map(str, jars))
    with (evidence / 'javac.log').open('w') as output:
        compiled = subprocess.run([str(args.swing_java_home / 'bin/javac'), '-proc:none', '-cp', classpath, '-d', str(classes), str(source)],
            stdout=output, stderr=subprocess.STDOUT, timeout=60)
        assert compiled.returncode == 0, 'Swing driver compilation failed; see javac.log'
    password = next(line.split('=', 1)[1].strip() for line in (home / 'conf/mirth.properties').read_text().splitlines()
                    if line.startswith('server.initialadminpassword ='))
    env = {key: value for key, value in os.environ.items() if key in ['PATH', 'HOME', 'USER', 'LOGNAME', 'LANG', 'TMPDIR']}
    env['OIE_SWING_TEST_PASSWORD'] = password
    private_home = runtime / 'swing-home'
    private_home.mkdir()
    # Use the actual stock servlet's client JVM contract, not a source checkout.
    isolated.require_listener(child, receipt['httpPort'])
    with urllib.request.urlopen(api.base.removesuffix('/api') + '/webstart.jnlp', timeout=30) as response:
        jnlp = response.read()
    (evidence / 'stock-webstart.jnlp').write_bytes(jnlp)
    metadata = ET.fromstring(jnlp)
    module_access = [argument for node in metadata.iter() for argument in shlex.split(node.get('java-vm-args', ''))
                     if argument.startswith(('--add-modules=', '--add-exports=', '--add-opens='))]
    write(evidence / 'client-jvm-module-access.json', {'shippedInput': 'stock-webstart.jnlp',
        'sha256': sha(evidence / 'stock-webstart.jnlp'), 'arguments': module_access})
    assert module_access, 'Stock JNLP did not supply module-access arguments; inspect retained contract before launching'
    for mode in ['save', 'verify']:
        folder = evidence / ('swing-' + mode)
        command = [str(args.swing_java_home / 'bin/java'), *module_access, '-Djava.awt.headless=false',
            '-Djava.util.prefs.PreferencesFactory=SwingConnectorRoundtrip$PrivatePreferencesFactory',
            '-Duser.home=' + str(private_home), '-cp', str(classes) + os.pathsep + classpath,
            'SwingConnectorRoundtrip', api.base.removesuffix('/api'), args.version, str(folder), mode, *channels]
        with (evidence / ('swing-' + mode + '.log')).open('w') as output:
            desktop = subprocess.Popen(command, cwd=private_home, env=env, stdout=output, stderr=subprocess.STDOUT)
            process = {'pid': desktop.pid, 'mode': mode, 'ownedRuntime': str(runtime)}
            write(evidence / ('swing-' + mode + '-process.json'), process)
            try:
                code = desktop.wait(timeout=260)
            finally:
                if desktop.poll() is None:
                    desktop.terminate()
                    try:
                        desktop.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        desktop.kill()
                        desktop.wait(timeout=10)
                process.update(exitCode=desktop.returncode, processExited=desktop.poll() is not None)
                write(evidence / ('swing-' + mode + '-process.json'), process)
            assert code == 0, 'Real Swing qualification failed; see swing-' + mode + '.log'
        observations = json.loads((folder / 'observations.json').read_text())
        assert observations and observations[-1] == {'check': 'passed', 'mode': mode}, \
            'Administrator exited without completing its qualification observations'
        expected_ids = [channels[0], channels[1], channels[0]]
        stage = 'native-save-action-persisted-full-properties' if mode == 'save' else 'fresh-process-reopen-preserves-full-properties'
        completed = [item for item in observations if item['check'] == stage]
        assert [item['channelId'] for item in completed] == expected_ids, 'Incomplete channel roundtrip observations'
        if mode == 'save':
            assert all(item['afterRevision'] > item['beforeRevision'] for item in completed), 'Save did not advance revision'
            assert [item['channelId'] for item in observations if item['check'] == 'leave-and-reopen-preserves-full-properties'] == expected_ids
        for channel_id in channels:
            final = api.call('/channels/' + channel_id, accept='application/xml')
            (evidence / (mode + '-after-' + channel_id + '.xml')).write_text(final)
            baseline = ET.fromstring((evidence / ('before-' + channel_id + '.xml')).read_text())
            saved = ET.fromstring(final)
            assert int(saved.findtext('revision')) > int(baseline.findtext('revision')), 'Native save did not advance stored revision'
            assert saved.findtext('description', '').startswith('Saved through real Swing Administrator: '), 'Native-save description missing'
            assert_properties((evidence / ('before-' + channel_id + '.xml')).read_text(), final)
    receipt.update(status='passed', swingHeadful=True, freshAdministratorProcessReopened=True, channelIds=channels,
        assertions=['stock-administrator-login', 'descriptor-client-shared-classpath', 'visible-reader-writer-placeholders',
                    'two-channel-panel-reuse', 'two-writer-selection-reuse', 'native-save-action', 'revision-advanced',
                    'full-property-equality', 'api-xml-property-equality', 'leave-and-reopen', 'fresh-process-reopen'])
    print('Real Swing save/reopen passed, including a fresh Administrator process', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--version', required=True, choices=['4.6.0', '4.7.0'])
    parser.add_argument('--plugin', type=Path, default=default_plugin())
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--swing-java-home', type=Path, required=True)
    parser.add_argument('--python', type=Path, default=Path('/opt/homebrew/bin/python3.14'))
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    from ci_engine import digest
    expected = json.loads((ROOT / 'ci/engines.json').read_text())[args.version]['sha256']
    assert digest(args.engine) == expected, 'Unexpected stock archive'
    isolated.observe_database = observe_database
    isolated.run(args, lambda *values: scenario(args, *values))
