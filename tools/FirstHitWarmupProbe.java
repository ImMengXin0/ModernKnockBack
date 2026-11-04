package cn.mx.modernkb.compat;

import java.lang.management.ManagementFactory;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;

/** Diagnostic only: no server or network. Run each comparison in a fresh JVM, not as a timing assertion. */
public final class FirstHitWarmupProbe {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        boolean prepared = args.length > 0 && args[0].equals("prepared");
        boolean flush = args.length > 1 && args[1].equals("flush");
        if (flush) Class.forName("org.dreeam.leaf.config.modules.gameplay.Knockback")
                .getField("flushKnockback").setBoolean(null, true);
        System.out.println("AUDIT prepared=" + prepared + " leaf-flush=" + flush);
        if (prepared) {
            // Reflection allows the same probe to run against the unmodified 2.3.0 classes.
            LeafVelocityDispatch.class.getMethod("initialize").invoke(null);
        }
        measure("dispatch-first", () -> LeafVelocityDispatch.stage());
        measure("dispatch-second", () -> LeafVelocityDispatch.stage());
        var filter = new HurtAnimationFilter(); // Production constructs this during enable.
        var packet = new ClientboundHurtAnimationPacket(42, 90f);
        measure("hurt-first", () -> filter.transform(packet, 42));
        measure("hurt-second", () -> filter.transform(packet, 42));
    }

    private interface Action { Object run() throws Exception; }
    private static void measure(String name, Action action) throws Exception {
        var counter = ManagementFactory.getClassLoadingMXBean();
        long loaded = counter.getTotalLoadedClassCount(), start = System.nanoTime();
        Object result = action.run();
        long elapsed = System.nanoTime() - start, classes = counter.getTotalLoadedClassCount() - loaded;
        if (result == null) throw new IllegalStateException("Missing diagnostic result");
        System.out.println("AUDIT " + name + " ns=" + elapsed + " classes=" + classes);
    }
}
