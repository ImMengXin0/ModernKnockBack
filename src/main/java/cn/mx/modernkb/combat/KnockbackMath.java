package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.HitContext;
import cn.mx.modernkb.config.KnockbackProfile;
import org.bukkit.util.Vector;

public final class KnockbackMath {
    private KnockbackMath() {}
    public static Vector modify(Vector impulse, HitContext hit, KnockbackProfile profile) {
        if(!finite(impulse)) throw new IllegalArgumentException("Non-finite native impulse");
        if(!profile.enabled()) return impulse.clone();
        double extra=extraMultiplier(hit, profile);
        Vector result=impulse.clone();
        double h=(hit.grounded()?profile.groundHorizontal():profile.airHorizontal())*extra;
        double v=(hit.grounded()?profile.groundVertical():profile.airVertical())*extra;
        result.setX(result.getX()*h).setZ(result.getZ()*h);
        if(!hit.syncOwnsVertical()) result.setY(result.getY()*v);
        return limit(result, hit, profile);
    }
    static double extraMultiplier(HitContext hit, KnockbackProfile profile) {
        double extra=1;
        if(hit.type().melee() || hit.type()==cn.mx.modernkb.api.HitType.SWEEP) {
            if(hit.sprinting()) extra*=profile.sprintMultiplier();
            if(hit.critical()) extra*=profile.criticalMultiplier();
            if(Double.isFinite(hit.attackCharge())) {
                double charge=Math.clamp(hit.attackCharge(),0,1);
                extra*=profile.weakMultiplier()+(1-profile.weakMultiplier())*charge;
            }
        }
        return extra;
    }
    /** Limits retain their 1.0 meaning: they cap impulses, not total velocity. */
    static Vector limit(Vector result, HitContext hit, KnockbackProfile profile) {
        double length=Math.hypot(result.getX(),result.getZ());
        if(profile.maxHorizontal()>0 && length>profile.maxHorizontal()) {
            double ratio=profile.maxHorizontal()/length;
            result.setX(result.getX()*ratio).setZ(result.getZ()*ratio);
        }
        if(!hit.syncOwnsVertical()) {
            if(profile.maxUpward()>0) result.setY(Math.min(result.getY(),profile.maxUpward()));
            if(profile.maxDownward()>0) result.setY(Math.max(result.getY(),-profile.maxDownward()));
        }
        return result;
    }
    public static boolean finite(Vector vector) {
        return vector!=null && Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }
}
