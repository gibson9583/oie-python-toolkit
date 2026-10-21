#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Validate the toolkit on an existing engine using only owned profiles/channels.

Never starts, signals, installs into, or deletes the engine or its database.
Run after standard plugin installation; no engine overlay is used.
"""
import argparse, copy, hashlib, http.cookiejar, json, os, socket, ssl, subprocess, time, urllib.parse, urllib.request, uuid, xml.etree.ElementTree as ET
from pathlib import Path
from live_engine_helpers import Api, NoRedirect, channel_xml, javascript_step, port, require_listener, write

class ExistingEngine:
    def __init__(self, pid): self.pid=pid
    def poll(self): return None if subprocess.run(['ps','-p',str(self.pid),'-o','pid='],capture_output=True).returncode==0 else 1

def connector_messages(document):
    message=document.get('message',document)
    entries=message.get('connectorMessages',{}).get('entry',[])
    if isinstance(entries,dict): entries=[entries]
    return {int(entry['int']):entry['connectorMessage'] for entry in entries}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--api',default='https://127.0.0.1:8443/api'); parser.add_argument('--pid',type=int,required=True)
    parser.add_argument('--evidence',type=Path,required=True); parser.add_argument('--username',default='admin')
    parser.add_argument('--password-env',default='OIE_VALIDATION_PASSWORD'); parser.add_argument('--inventory-only',action='store_true')
    args=parser.parse_args(); address=urllib.parse.urlsplit(args.api)
    if address.hostname not in ('127.0.0.1','localhost','::1'): parser.error('This ownership fixture only supports loopback API addresses')
    password=os.environ.get(args.password_env)
    if password is None: parser.error('Set the password environment variable; credentials are never written to evidence')
    engine=ExistingEngine(args.pid); listener=address.port or (443 if address.scheme=='https' else 80)
    api=Api(args.api,lambda:require_listener(engine,listener))
    api.opener=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
    login=api.call('/users/_login','POST',urllib.parse.urlencode({'username':args.username,'password':password}),'application/x-www-form-urlencoded')
    assert 'SUCCESS' in json.dumps(login),'Configured credentials were not accepted'
    evidence=args.evidence.resolve(); evidence.mkdir(parents=True,exist_ok=True); evidence.chmod(0o700)
    # Stock OIE's CHANNEL.NAME column is limited to 40 characters.
    run=str(uuid.uuid4()); prefix='Python validation '+run[:8]
    py='/extensions/oie-python-toolkit'; profiles=[]; channels=[]; status='running'; failure=None
    owner={'runId':run,'prefix':prefix,'enginePid':args.pid,'api':args.api,'engineProcessOwned':False,'engineFilesModified':False,'databaseFilesModified':False,'profiles':profiles,'channels':channels}
    write(evidence/'ownership.json',owner)
    def save(cid,model):
        # The ID was allocated by this run. Existing engine channel metadata uses
        # edit timestamps, so override only this run's own UUID after checked read.
        assert cid in channels
        receipt=api.call('/channels/'+cid+'?override=true','PUT',model,'application/xml')
        assert receipt is True or (isinstance(receipt,dict) and receipt.get('boolean') is True),receipt
    def create_channel(model,ingress):
        xml=ET.fromstring(model); cid=xml.find('id').text; xml.find('name').text=prefix+' '+cid[:8]
        channels.append(cid); write(evidence/'ownership.json',owner)
        saved=ET.tostring(xml,encoding='unicode'); (evidence/(cid+'.xml')).write_text(saved)
        save(cid,saved); api.call('/channels/'+cid+'/_deploy?returnErrors=true','POST')
        source=Api(f'http://127.0.0.1:{ingress}',lambda:require_listener(engine,ingress))
        return cid,source,saved
    try:
        for filename,path,accept in [('server-version','/server/version','text/plain'),('channel-statuses-before','/channels/statuses','application/json'),('plugins','/extensions/plugins','application/json')]:
            write(evidence/(filename+'.json'),api.call(path,accept=accept))
        inventory=api.call('/channels',accept='application/xml')
        document=ET.fromstring(inventory)
        channel_hashes={}
        for channel in document:
            cid=channel.findtext('id')
            if cid:
                normalized=ET.canonicalize(ET.tostring(channel,encoding='unicode'))
                channel_hashes[cid]=hashlib.sha256(normalized.encode()).hexdigest()
        write(evidence/'existing-channel-hashes-before.json',channel_hashes)
        if args.inventory_only: status='inventory-complete'; return
        info=api.call(py+'/runtime'); write(evidence/'runtime-info.json',info)
        assert info['runtimes'] and info['runtimes'][0]['available'],info
        runtime_id=info['runtimes'][0]['id']; source_id=info['packageSources'][0]['id']
        for label,requirements in [('stdlib',''),('marshmallow','marshmallow==4.3.1')]:
            listing=api.call(py+'/profiles'); request={'requestId':str(uuid.uuid4()),'expectedRegistryEpoch':listing['registryEpoch'],'name':prefix+' '+label,
                'runtimeId':runtime_id,'packageSourceId':source_id,'requirementsText':requirements}
            created=api.call(py+'/profiles','POST',request); profile=created['profile']; profiles.append({'id':profile['id'],'label':label}); write(evidence/'ownership.json',owner)
            prepared=api.call(py+'/profiles/'+profile['id']+'/prepare','POST',{'requestId':str(uuid.uuid4()),'expectedRegistryEpoch':profile['registryEpoch'],'expectedRevision':profile['revision']},status=(200,202))
            job=prepared['job']; deadline=time.monotonic()+180
            while job['state'] not in ('READY','FAILED','CANCELLED','INTERRUPTED','SUPERSEDED') and time.monotonic()<deadline:
                time.sleep(.25); job=api.call(py+'/jobs/'+job['id'])
            write(evidence/(label+'-job.json'),job); write(evidence/(label+'-preparation-log.json'),api.call(py+'/jobs/'+job['id']+'/logs'))
            assert job['state']=='READY',job
        profile=api.call(py+'/profiles/'+profiles[0]['id'])
        script={'requestId':str(uuid.uuid4()),'environmentProfileId':profile['id'],'expectedRegistryEpoch':profile['registryEpoch'],'expectedEnvironmentRevision':profile['revision'],
                'role':'source-transformer','pythonScript':"raise Exception('compile must not run top level')\npass\n",'contractVersion':1}
        validation=api.call(py+'/scripts/validate','POST',script); assert validation['status']=='valid',validation; write(evidence/'compile-only.json',validation)
        dataclass_writer = (
            "from __future__ import annotations\n"
            "from dataclasses import dataclass\n"
            "@dataclass\n"
            "class Payload:\n"
            "    value: str\n"
            "connectorMap['writer'] = 'A'\n"
            "return 'A|' + Payload(payload).value\n"
        )
        test = dict(script, requestId=str(uuid.uuid4()), role='writer',
                    pythonScript=dataclass_writer, sampleContext={'payload': 'sample'})
        tested=api.call(py+'/scripts/test','POST',test); assert tested['status']=='success',tested; write(evidence/'sample-test.json',tested)
        cid = str(uuid.uuid4())
        ingress = port()
        model = ET.fromstring(channel_xml(cid, ingress, profile['id'], 'A',
                                         writer_source=dataclass_writer))
        elements = model.find('sourceConnector/transformer/elements')
        elements[0].find('script').text += "\nglobalChannelMap.put('$t', 'literal-key');"
        python_step = elements.find('com.mirth.connect.plugins.python.PythonStep')
        python_step.find('pythonScript').text += (
            "assert globalChannelMap['$t'] == 'literal-key'\n"
        )
        cid, source, model = create_channel(ET.tostring(model, encoding='unicode'), ingress)
        result=source.call('/python/','POST','hello','text/plain'); write(evidence/'all-surfaces-response.json',result)
        assert result=='A|hello|js-before|source|js-after|destination|response',result
        write(evidence/'all-surfaces-message.json',api.call('/channels/'+cid+'/messages/1'))
        model_b=ET.fromstring(channel_xml(cid,ingress,profile['id'],'B')); model_b.find('name').text=prefix+' '+cid[:8]; save(cid,ET.tostring(model_b,encoding='unicode'))
        assert source.call('/python/','POST','saved','text/plain').startswith('A|')
        api.call('/channels/'+cid+'/_deploy?returnErrors=true','POST'); result=source.call('/python/','POST','redeployed','text/plain')
        assert result.startswith('B|'),result; write(evidence/'current-deployment.json',result)
        # Compare the same raw-string pipeline locally; timings include the
        # ownership check and HTTP client, and are not a throughput guarantee.
        baseline_id=str(uuid.uuid4()); baseline_port=port()
        baseline=ET.fromstring(channel_xml(baseline_id,baseline_port,profile['id'],'J'))
        destination=baseline.find('destinationConnectors/connector')
        previous=destination.find('properties'); defaults=copy.deepcopy(previous.find('destinationConnectorProperties'))
        destination.remove(previous)
        properties=ET.Element('properties',{'class':'com.mirth.connect.connectors.js.JavaScriptDispatcherProperties','version':'4.6.0'})
        properties.append(defaults); ET.SubElement(properties,'script').text="return 'J|' + connectorMessage.getEncodedData();"
        destination.insert(2,properties); destination.find('transportName').text='JavaScript Writer'
        for path,suffix in [('sourceConnector/transformer/elements','source'),('destinationConnectors/connector/transformer/elements','destination'),
                            ('destinationConnectors/connector/responseTransformer/elements','response')]:
            elements=baseline.find(path)
            for position,item in enumerate(list(elements)):
                if item.tag=='com.mirth.connect.plugins.python.PythonStep':
                    elements.remove(item); elements.insert(position,javascript_step("msg = msg + '|"+suffix+"';",'JavaScript '+suffix))
        _,baseline_source,_=create_channel(ET.tostring(baseline,encoding='unicode'),baseline_port)
        timings={}
        for label,transport,marker in [('python',source,'B'),('javascript',baseline_source,'J')]:
            samples=[]
            for iteration in range(8):
                started=time.monotonic(); observed=transport.call('/python/','POST','latency','text/plain')
                samples.append((time.monotonic()-started)*1000)
                assert observed==marker+'|latency|js-before|source|js-after|destination|response',observed
            timings[label]=samples
        write(evidence/'local-pipeline-latency-ms.json',{'samples':timings,'unit':'ms','scope':'Sequential local HTTP wall time including ownership checks; no throughput or cross-platform claim'})
        queue_id=str(uuid.uuid4()); queue_port=port()
        queue_a=ET.fromstring(channel_xml(queue_id,queue_port,profile['id'],'QA'))
        queue_props=queue_a.find('destinationConnectors/connector/properties/destinationConnectorProperties')
        queue_props.find('queueEnabled').text='true'; queue_props.find('sendFirst').text='false'; queue_props.find('retryIntervalMillis').text='1000'
        _,queue_source,queue_saved=create_channel(ET.tostring(queue_a,encoding='unicode'),queue_port)
        api.call('/channels/'+queue_id+'/connector/1/_stop?returnErrors=true','POST')
        queue_source.call('/python/','POST','queued','text/plain')
        queued=api.call('/channels/'+queue_id+'/messages/1'); write(evidence/'queued-before-redeploy.json',queued)
        assert 'QUEUED' in json.dumps(queued),queued
        # Save and prepare a changed dependency lock under the same UUID while A
        # remains deployed. The stored Sent document still contains executable A.
        current_profile=api.call(py+'/profiles/'+profile['id'])
        definition={key:current_profile.get(key) for key in ('name','description','runtimeId','packageSourceId')}
        definition.update(requestId=str(uuid.uuid4()),expectedRegistryEpoch=current_profile['registryEpoch'],expectedRevision=current_profile['revision'],requirementsText='marshmallow==4.3.1')
        changed=api.call(py+'/profiles/'+profile['id'],'PUT',definition)['profile']
        preparation=api.call(py+'/profiles/'+profile['id']+'/prepare','POST',{'requestId':str(uuid.uuid4()),'expectedRegistryEpoch':changed['registryEpoch'],'expectedRevision':changed['revision']},status=(200,202))
        job=preparation['job']; deadline=time.monotonic()+180
        while job['state'] not in ('READY','FAILED','CANCELLED','INTERRUPTED','SUPERSEDED') and time.monotonic()<deadline:
            time.sleep(.25); job=api.call(py+'/jobs/'+job['id'])
        write(evidence/'changed-lock-job.json',job); assert job['state']=='READY',job
        queue_b=ET.fromstring(channel_xml(queue_id,queue_port,profile['id'],'QB',writer_source="from oie_python import Response\nfrom importlib.metadata import version\nreturn Response(data='QB|' + version('marshmallow') + '|' + payload)\n"))
        queue_b.find('name').text=ET.fromstring(queue_saved).find('name').text
        queue_props=queue_b.find('destinationConnectors/connector/properties/destinationConnectorProperties')
        queue_props.find('queueEnabled').text='true'; queue_props.find('sendFirst').text='false'; queue_props.find('retryIntervalMillis').text='1000'
        save(queue_id,ET.tostring(queue_b,encoding='unicode')); api.call('/channels/'+queue_id+'/_deploy?returnErrors=true','POST')
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            recovered=api.call('/channels/'+queue_id+'/messages/1')
            if 'QB|4.3.1|' in json.dumps(recovered): break
            time.sleep(.25)
        write(evidence/'queued-after-redeploy.json',recovered)
        assert 'QB|4.3.1|queued|js-before|source|js-after|destination|response' in json.dumps(recovered),recovered
        assert connector_messages(recovered)[1]['status']=='SENT',recovered
        # The previously active B channel still owns A's environment until redeploy.
        assert source.call('/python/','POST','after-profile-edit','text/plain').startswith('B|')
        marsh_id=str(uuid.uuid4()); marsh_port=port(); _,marsh_source,_=create_channel(channel_xml(marsh_id,marsh_port,profiles[1]['id'],'M',marshmallow=True),marsh_port)
        result=marsh_source.call('/python/','POST',{'patient_id':'P123'}); assert result=='M|P123|marshmallow|js-after|destination|response',result
        write(evidence/'marshmallow-response.json',result)
        reader_id=str(uuid.uuid4()); reader_port=port(); create_channel(channel_xml(reader_id,reader_port,profile['id'],'R',reader=True),reader_port)
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            count=api.call('/channels/'+reader_id+'/messages/maxMessageId'); number=count.get('long',0) if isinstance(count,dict) else count
            if number>=2:
                second=api.call('/channels/'+reader_id+'/messages/2')
                if connector_messages(second).get(1,{}).get('status')=='SENT': break
            time.sleep(.25)
        api.call('/channels/'+reader_id+'/_stop?returnErrors=true','POST')
        first=api.call('/channels/'+reader_id+'/messages/1'); second=api.call('/channels/'+reader_id+'/messages/2')
        write(evidence/'reader-message-1.json',first); write(evidence/'reader-message-2.json',second)
        assert set(connector_messages(first))=={0},first
        destinations=connector_messages(second)
        assert set(destinations)=={0,1} and destinations[1]['status']=='SENT',second
        assert 'R|AP8=|destination|response' in json.dumps(second),second
        for label,code,timeout in [('bad-return',"connectorMap['partial'] = 'must-not-apply'\nreturn {'invalid':'response'}\n",30000),
                ('timeout',"import time\ntime.sleep(60)\n",300)]:
            bad_id=str(uuid.uuid4()); bad_port=port(); _,bad_source,_=create_channel(channel_xml(bad_id,bad_port,profile['id'],label,writer_source=code,timeout=timeout),bad_port)
            started=time.monotonic(); bad_source.call('/python/','POST','sample','text/plain'); elapsed=time.monotonic()-started
            msg=api.call('/channels/'+bad_id+'/messages/1'); write(evidence/(label+'-message.json'),msg)
            assert connector_messages(msg)[1]['status']=='ERROR',msg
            if label == 'bad-return':
                # Sent content includes the script text. Inspect the persisted
                # map itself to prove that the failed call applied no mutation.
                connectorMap = connector_messages(msg)[1]['connectorMapContent']['content']
                assert connectorMap == {'@class': 'map'}, connectorMap
            else: assert elapsed<8 and 'deadline' in json.dumps(msg).lower(); write(evidence/'timeout-bound.json',{'elapsedSeconds':elapsed,'boundSeconds':8})
        # Invalid source can be saved, but its authoritative deploy must fail.
        invalid_id=str(uuid.uuid4()); invalid_port=port(); invalid=ET.fromstring(channel_xml(invalid_id,invalid_port,profile['id'],'invalid'))
        invalid.find('name').text=prefix+' '+invalid_id[:8]; invalid.find('sourceConnector/transformer/elements/com.mirth.connect.plugins.python.PythonStep/pythonScript').text='msg = (\n'
        channels.append(invalid_id); write(evidence/'ownership.json',owner); save(invalid_id,ET.tostring(invalid,encoding='unicode'))
        rejected=api.call('/channels/'+invalid_id+'/_deploy?returnErrors=true','POST',status=(500,)); write(evidence/'invalid-deploy.json',rejected)
        assert source.call('/python/','POST','after-failure','text/plain').startswith('B|')
        write(evidence/'runtime-final.json',api.call(py+'/runtime')); status='passed'
        print('Live acceptance: both profiles prepared; all runtime surfaces, current deployment, Reader bytes/selection, invalid returns, timeout and invalid deployment passed.',flush=True)
    except BaseException as error:
        status='failed'; failure=repr(error); raise
    finally:
        cleanup=[]
        for cid in reversed(channels):
            record={'channelId':cid}
            try:
                # No existing channel IDs are ever admitted to this ownership list.
                assert len(cid)==36 and cid in channels
                api.call('/channels/'+cid+'/_undeploy?returnErrors=true','POST',status=(200,204,404,500))
                api.call('/channels/'+cid,'DELETE',status=(200,204,404)); record['removed']=True
            except Exception as error: record['error']=repr(error)
            cleanup.append(record)
        for owned in reversed(profiles):
            record={'profileId':owned['id']}
            try:
                current=api.call(py+'/profiles/'+owned['id']); assert current['name'].startswith(prefix)
                query=urllib.parse.urlencode({'requestId':str(uuid.uuid4()),'expectedRegistryEpoch':current['registryEpoch'],'expectedRevision':current['revision']})
                api.call(py+'/profiles/'+owned['id']+'?'+query,'DELETE'); record['archived']=True
            except Exception as error: record['error']=repr(error)
            cleanup.append(record)
        write(evidence/'cleanup.json',{'engineProcessTouched':False,'engineFilesTouched':False,'databaseFilesTouched':False,'ownedResources':cleanup,
             'artifactPolicy':'Shared prepared artifacts retained for the engine lease-aware GC; no cache path deleted by fixture.'})
        try: write(evidence/'channel-statuses-after.json',api.call('/channels/statuses'))
        except Exception: pass
        try:
            after=ET.fromstring(api.call('/channels',accept='application/xml'))
            hashes={channel.findtext('id'):hashlib.sha256(ET.canonicalize(ET.tostring(channel,encoding='unicode')).encode()).hexdigest()
                    for channel in after if channel.findtext('id') in channel_hashes}
            write(evidence/'existing-channel-comparison.json',{'before':channel_hashes,'after':hashes,'unchanged':hashes==channel_hashes})
        except Exception as error: write(evidence/'existing-channel-comparison.json',{'error':repr(error)})
        write(evidence/'result.json',{'status':status,'error':failure,'runId':run})
        for path in evidence.iterdir():
            if path.is_file(): path.chmod(0o600)

if __name__=='__main__': main()
