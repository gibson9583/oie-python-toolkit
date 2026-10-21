/* SPDX-License-Identifier: MPL-2.0 */
import React from 'react';import {createRoot} from 'react-dom/client';import {platform} from '@oie/web-shell';import {CodeEditor} from '/core/codeeditor.js';
platform.React=React;
if(new URLSearchParams(location.search).has('monaco')){const create=platform.createCodeEditor.bind(platform);platform.createCodeEditor=opts=>{window.lastEditor=create(opts);return window.lastEditor;};}
else platform.setCodeEditorFactory(opts=>{window.lastEditor=new CodeEditor(opts);return window.lastEditor;});
platform.store.setState('serverId','node');platform.store.setState('editingChannel',{id:'channel'});
// The real plugin loader supplies authenticated, session-fenced asset access.
// This fixture serves only the plugin-owned grammar through that same interface.
window.pluginAssetPaths=[];
const context={getAssetText:async relativePath=>{
    if(relativePath!=='web/python-language.json')throw new Error('Unexpected plugin asset: '+relativePath);
    window.pluginAssetPaths.push(relativePath);
    const response=await fetch('/plugins/python-runtime/'+relativePath);
    if(!response.ok)throw new Error('Plugin asset unavailable: '+response.status);
    return response.text();
}};
const plugin=await import('/toolkit.js');plugin.register(platform,context);window.platform=platform;
window.showPanel=(kind,options={})=>{
    const connectorKind=kind==='reader'||kind==='writer';
    const def=kind==='settings'?platform.settingsPanels().find(v=>v.id==='python-runtime'):kind==='template'?platform.codeTemplateTypes().get('python-function'):connectorKind?platform.connectorPanel(kind==='reader'?'Python Reader':'Python Writer',kind==='reader'?'SOURCE':'DESTINATION'):kind==='rule'?platform.ruleType('com.mirth.connect.plugins.python.PythonRule'):platform.stepType('com.mirth.connect.plugins.python.PythonStep');
    const template={id:'template',name:'Normalize patient',contextSet:{delegate:{contextType:['SOURCE_FILTER_TRANSFORMER']}}};
    const value=kind==='settings'?null:connectorKind?def.defaults('4.6.0'):kind==='template'?def.create({version:'4.6.0',template}):def.create();if(value){if(!options.useDefault)value.environmentProfileId='profile';Object.assign(value,options.subject||{});}window.subject=value;
    window.root?.unmount();const channel=options.channel||{id:'channel'};platform.store.setState('editingChannel',channel);
    const app=document.querySelector('#app');app.replaceChildren();
    const host=document.createElement('div');host.className='view-body flush';app.appendChild(host);
    if(kind==='step'||kind==='rule')host.innerHTML='<table><tbody><tr class="selected" data-path="0"><td>Python</td></tr></tbody></table>';
    const content=document.createElement('div');host.appendChild(content);window.root=createRoot(content);
    const render=value=>window.root.render(React.createElement(def.component,{...(kind==='template'?{properties:value,template,readOnly:!!options.readOnly}:connectorKind?{properties:value,connector:{metaDataId:kind==='reader'?0:1},channel}:{element:value,...(kind==='rule'?{role:options.role}:{})}),onChange:()=>window.changes=(window.changes||0)+1,setSave:fn=>window.save=fn,markDirty:()=>window.dirty=true,markClean:()=>window.dirty=false,setTasks:()=>{}}));
    window.updatePanelSubject=value=>{window.subject=value;render(value);};render(value);
};window.showPanel('settings');window.ready=true;
