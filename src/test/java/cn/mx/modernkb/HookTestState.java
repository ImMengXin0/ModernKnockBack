package cn.mx.modernkb;

import java.util.HashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FishHook;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import static org.mockito.Mockito.*;

final class HookTestState {
    static void install(FishHook hook) {
        var values = new HashMap<NamespacedKey, Byte>();
        var data = mock(PersistentDataContainer.class);
        when(hook.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(data).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), any(Byte.class));
    }
}
