// npm install --no-save mineflayer@4.37.1, or set NODE_PATH to an existing install.
const mineflayer = require('mineflayer');
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
async function main() {
  const a = await connect('KBQAAttacker');
  if (process.argv[2] !== 'stop') {
    await connect('KBQAVictim'); await sleep(2000);
    await awaitMessage(a, () => a.chat('/firsthit bench KBQAVictim ' + (process.argv[2] || 'plain')), /FIRST_HIT_COMPLETE/);
  }
  await awaitMessage(a, () => a.chat('/firsthit stop'), /FIRST_HIT_STOPPING/);
}
const timeout = setTimeout(() => { console.error('TEST_TIMEOUT'); process.exit(2); }, 90000);
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => {
  clearTimeout(timeout); for (const bot of bots) bot.quit(); await sleep(500);
});
