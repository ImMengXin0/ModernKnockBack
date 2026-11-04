// npm install --no-save mineflayer@4.37.1, or set NODE_PATH to an existing install.
const mineflayer = require('mineflayer');
const decodeVelocity = require('./velocity-packet.cjs');
const bots = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const originalLog = console.log, originalError = console.error;
// mineflayer 4.37.1 has an unrelated 1.21.11 particle decoder warning.
for (const key of ['log', 'error']) console[key] = (...args) => {
  if (args.some(x => String(x?.stack || x).includes('packet_world_particles'))) return;
  (key === 'log' ? originalLog : originalError)(...args);
};
function awaitMessage(bot, action, pattern) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { bot.removeListener('messagestr', listener); reject(Error('timeout')); }, 30000);
    function listener(message) {
      if (!pattern.test(message) && !message.includes('FIRST_HIT_FAIL')) return;
      clearTimeout(timer); bot.removeListener('messagestr', listener);
      message.includes('FIRST_HIT_FAIL') ? reject(Error(message)) : resolve();
    }
    bot.on('messagestr', listener); action();
  });
}
async function connect(username) {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:27183,username,auth:'offline',version:'1.21.11'});
  bot.physicsEnabled = false; bots.push(bot);
  bot.on('messagestr', message => { if (message.includes('FIRST_HIT_')) console.log(message); });
  await new Promise((resolve, reject) => { bot.once('spawn', resolve); bot.once('error', reject); });
  return bot;
}
function networkCapture(a, v, cycle) {
  let pending;
  const records = [];
  function packet(data, meta, raw) {
    if (!pending || data.entityId !== v.entity.id || !['entity_velocity', 'hurt_animation', 'damage_event'].includes(meta.name)) return;
    const afterSendMs = Number(process.hrtime.bigint()-pending.start)/1e6;
    if (meta.name === 'entity_velocity') data = decodeVelocity(raw);
    pending.packets.push({name:meta.name, afterSendMs, data});
  }
  function message(text) {
    const ready = text.match(/FIRST_HIT_NET_READY n=(\d+) target=(\d+)/);
    if (ready) {
      const target = a.entities[Number(ready[2])];
      if (!target) throw Error('Target missing on attacker client');
      pending = {cycle, n:Number(ready[1]), start:process.hrtime.bigint(), packets:[]};
      records.push(pending);
      a.attack(target);
    }
  }
  v._client.on('packet', packet); a.on('messagestr', message);
  return () => {
    v._client.removeListener('packet', packet); a.removeListener('messagestr', message);
    for (const record of records) {
      const {start, ...printable} = record;
      console.log('FIRST_HIT_CLIENT ' + JSON.stringify(printable));
      if (!record.packets.some(p => p.name === 'entity_velocity' && Math.abs(p.data.velocity.x) > .01))
        throw Error('No actual horizontal knockback packet for sample ' + record.n);
      if (process.env.EXPECT_CLASSIC === '1') {
        const kb = record.packets.find(p => p.name === 'entity_velocity' && Math.abs(p.data.velocity.x) > .01).data.velocity;
        if (Math.abs(kb.x-.4)>.00004 || Math.abs(kb.y-.36075)>.00004 || Math.abs(kb.z)>.00004)
          throw Error('Unexpected classic knockback packet: '+JSON.stringify(kb));
      }
      if (!record.packets.some(p => p.name === 'hurt_animation' || p.name === 'damage_event'))
        throw Error('No actual hurt/damage packet for sample ' + record.n);
    }
    if (records.length !== 6) throw Error('Expected six client-generated attacks');
  };
}
async function main() {
  let a = await connect('KBQAAttacker');
  if (process.argv[2] !== 'stop') {
    let v = await connect('KBQAVictim');
    const rejoin = ['rejoin', 'network-rejoin'].includes(process.argv[2]);
    const network = ['network', 'network-rejoin'].includes(process.argv[2]);
    for (let cycle = 0; cycle < (rejoin ? 3 : 1); cycle++) {
      if (cycle > 0) {
        a.quit(); v.quit(); await sleep(1500);
        a = await connect(cycle === 1 ? 'KBQAAttacker' : 'KBQANewAttack');
        v = await connect(cycle === 1 ? 'KBQAVictim' : 'KBQANewVictim');
      }
      await sleep(2000);
      const flavor = rejoin ? 'plain' : (process.argv[2] || 'plain');
      const finishCapture = network ? networkCapture(a, v, cycle) : () => {};
      await awaitMessage(a, () => a.chat('/firsthit ' + (network ? 'network ' : 'bench ') + v.username + ' ' + flavor + ' cycle' + cycle), /FIRST_HIT_COMPLETE/);
      finishCapture();
    }
  }
  await awaitMessage(a, () => a.chat('/firsthit stop'), /FIRST_HIT_STOPPING/);
}
const timeout = setTimeout(() => { console.error('TEST_TIMEOUT'); process.exit(2); }, 90000);
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  clearTimeout(timeout); for (const bot of bots) bot.quit(); await sleep(500);
});
