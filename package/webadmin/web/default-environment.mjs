/* SPDX-License-Identifier: MPL-2.0 */
import {DEFAULT_PROFILE_ID,DEFAULT_PROFILE_NAME,assertProfileView} from './ui-model.mjs';

export function defaultProfile(list){
    if(list?.defaultProfileId!==DEFAULT_PROFILE_ID||!Array.isArray(list.profiles)||list.profiles.some(profile=>!profile||typeof profile.id!=='string'||!profile.id))return null;
    const matches=list.profiles.filter(profile=>profile?.id===DEFAULT_PROFILE_ID);
    if(matches.length!==1||matches[0].builtInDefault!==true||matches[0].archived)return null;
    try{return assertProfileView(matches[0]);}catch{return null;}
}
export function automaticEnvironment(subject,list){
    if(subject.environmentProfileId!=null&&subject.environmentProfileId!=='')return null;
    const profile=defaultProfile(list);return profile?{id:profile.id,name:profile.name||DEFAULT_PROFILE_NAME}:null;
}
export function selectedEnvironment(subject,list){
    if(subject.environmentProfileId===DEFAULT_PROFILE_ID)return defaultProfile(list);
    return Array.isArray(list?.profiles)?list.profiles.find(profile=>profile?.id===subject.environmentProfileId)||null:null;
}
export function environmentState(profile){
    return (profile?.readiness?.pythonVersion?`CPython ${profile.readiness.pythonVersion} · `:'')+(profile?.readiness?.state?.replaceAll('-',' ')||'not prepared');
}

// Historical connector hints are user data; update only the reserved built-in label.
export function unresolvedEnvironmentName(subject){
    return subject.environmentProfileId===DEFAULT_PROFILE_ID?DEFAULT_PROFILE_NAME:subject.environmentNameHint||subject.environmentProfileId;
}
