// Test-only 1.21.11 decoder matching net.minecraft.network.LpVec3.read.
// minecraft-protocol's installed lpVec3 reader uses LE for the final int;
// the server writes two individual bytes followed by a BIG-endian int.
function varint(buffer, offset) {
  let value = 0, shift = 0, byte;
  do {
    if (offset >= buffer.length || shift >= 35) throw Error('Invalid VarInt');
    byte = buffer[offset++]; value += (byte & 127) * 2 ** shift; shift += 7;
  } while (byte & 128);
  return {value, offset};
}
function velocityPacket(buffer) {
  const packet = varint(buffer, 0);
  if (packet.value !== 0x63) throw Error('Not a 1.21.11 entity_velocity packet');
  const entity = varint(buffer, packet.offset), offset = entity.offset;
  const a = buffer[offset];
  if (a === 0) return {entityId:entity.value, velocity:{x:0,y:0,z:0}};
  const packed = buffer.readUInt32BE(offset + 2) * 65536 + buffer[offset + 1] * 256 + a;
  const scale = (a & 3) + ((a & 4) ? varint(buffer, offset + 6).value * 4 : 0);
  const component = shift => (Math.min(Math.floor(packed / 2 ** shift) & 32767, 32766) * 2 / 32766 - 1) * scale;
  return {entityId:entity.value, velocity:{x:component(3),y:component(18),z:component(33)}};
}
module.exports = velocityPacket;
if (require.main === module) {
  const assert = require('node:assert/strict');
  const expected = {x:.4,y:.36075,z:0};
  const quantize = n => Math.round((n*.5+.5)*32766);
  const packed = 1 + quantize(expected.x)*2**3 + quantize(expected.y)*2**18 + quantize(expected.z)*2**33;
  const bytes = Buffer.alloc(8); bytes[0]=0x63; bytes[1]=2;
  bytes[2]=packed%256; bytes[3]=Math.floor(packed/256)%256; bytes.writeUInt32BE(Math.floor(packed/65536),4);
  const actual=velocityPacket(bytes);
  for(const axis of ['x','y','z']) assert.ok(Math.abs(actual.velocity[axis]-expected[axis])<.00004);
  assert.deepEqual(velocityPacket(Buffer.from([0x63,2,0])),{entityId:2,velocity:{x:0,y:0,z:0}});
  console.log('Velocity decoder checks passed');
}
