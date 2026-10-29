import test from 'node:test';
import assert from 'node:assert/strict';
import { createContext } from '../host/cordis.js';
import { Context } from '@deepseek-ai/cordis';
test('real Cordis keeps host services and defers missing dependencies', async () => {
 const ctx = createContext();
 assert.ok(Context.is(ctx));
 assert.equal(typeof ctx.llm.chat, 'function');
 assert.equal(typeof ctx.credentials.get, 'function');
 let applied = false;
 await ctx.plugin({name:'deferred',inject:['later'],apply(){applied=true;}});
 assert.equal(applied,false);
 ctx.provide('later',{});
 await new Promise(r=>setTimeout(r,20));
 assert.equal(applied,true);
 await ctx.dispose();
});
test('llm registry provides models and forwards calls to native gateway', async () => {
 const ctx=createContext({nativeCall:async(method,payload)=>({method,payload})});
 ctx.llm.registerProvider('fixture',{models:()=>['demo']});
 assert.deepEqual(await ctx.llm.providers.get('fixture').models(),['demo']);
 const result=await ctx.llm.chat({messages:[{role:'user',content:'hello'}]});
 assert.equal(result.method,'llm.chat');
 assert.equal(result.payload.messages[0].content,'hello');
 await ctx.dispose();
});
