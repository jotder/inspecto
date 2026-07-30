package com.gamma.asn.plugins.vendor;

import com.gamma.asn.plugin.PluginContext;
import com.gamma.asn.plugin.TransformFunction;
import com.gamma.asn.plugin.TransformFunctionProvider;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the legacy vendor functions under their config names. Only functions the
 * production tx configs actually invoke are ported; names the configs call that legacy
 * never implemented (getStartEndTime, convertedClientDate, interOperatorIdentifiers,
 * subscriptionId, firstKey) stay unregistered on purpose — legacy resolved them to null
 * and the corpus rows depend on that.
 *
 * <p>Dispatch mirrors the legacy reflective overload match: an argument of the wrong
 * type throws here (ClassCastException) exactly where legacy found no overload, and the
 * registry turns both into a null field value.
 */
public final class LegacyVendorFunctions implements TransformFunctionProvider {

    @Override
    public Map<String, TransformFunction> functions(PluginContext context) {
        VendorTransforms tx = new VendorTransforms(context.lookups());
        Map<String, TransformFunction> f = new LinkedHashMap<>();

        // Ericsson CCN
        f.put("ccnEventType", a -> VendorTransforms.ccnEventType(map(a, 0), str(a, 1)));
        f.put("ccnEventTypeKey", a -> VendorTransforms.ccnEventTypeKey(map(a, 0), str(a, 1)));
        f.put("ccnSubscriberType", a -> VendorTransforms.ccnSubscriberType(map(a, 0), str(a, 1), str(a, 2)));
        f.put("ccnFnfNumber", a -> VendorTransforms.ccnFnfNumber(map(a, 0), str(a, 1), str(a, 2)));
        f.put("ccnCellId", a -> VendorTransforms.ccnCellId(map(a, 0)));
        f.put("ccnSmscCenter", a -> VendorTransforms.ccnSmscCenter(map(a, 0)));
        f.put("ccnServedMsrn", a -> VendorTransforms.ccnServedMsrn(map(a, 0)));
        f.put("ccnChargedAccName", a -> VendorTransforms.ccnChargedAccName(map(a, 0)));
        f.put("ccnDedicatedCharge", a -> VendorTransforms.ccnDedicatedCharge(map(a, 0)));
        f.put("ccnSrvTypeKey", a -> VendorTransforms.ccnSrvTypeKey(map(a, 0), str(a, 1), str(a, 2)));
        f.put("ccnBillablePulse", a -> VendorTransforms.ccnBillablePulse(bigInt(a, 0), str(a, 1)));
        f.put("ccnOriginalDur", a -> VendorTransforms.ccnOriginalDur(map(a, 0)));
        f.put("ccnZeroDurationInd", a -> VendorTransforms.ccnZeroDurationInd(bigInt(a, 0)));
        f.put("ccnEventDir", a -> VendorTransforms.ccnEventDir(map(a, 0), str(a, 1)));
        f.put("ccnRoamingPosition", a -> VendorTransforms.ccnRoamingPosition(map(a, 0)));
        f.put("ccnGsnAddres", a -> VendorTransforms.ccnGsnAddres(map(a, 0)));
        f.put("ccnGprsTotalVolume", a -> VendorTransforms.ccnGprsTotalVolume(map(a, 0)));
        f.put("ccnOtherMsisdn", a -> VendorTransforms.ccnOtherMsisdn(map(a, 0)));
        f.put("ccnRateEventType", a -> VendorTransforms.ccnRateEventType(map(a, 0), str(a, 1)));
        f.put("ccnServiceKey", a -> VendorTransforms.ccnServiceKey(map(a, 0), str(a, 1), str(a, 2)));
        f.put("ccnCharge", a -> VendorTransforms.ccnCharge(map(a, 0)));

        // Ericsson OCC
        f.put("occServedSubscriptionIds", a -> VendorTransforms.occServedSubscriptionIds(map(a, 0)));
        f.put("occGprsDedicatedCharge", a -> VendorTransforms.occGprsDedicatedCharge(map(a, 0)));
        f.put("occGprsOriginalDur", a -> VendorTransforms.occGprsOriginalDur(map(a, 0)));
        f.put("occGprsGsnAddres", a -> VendorTransforms.occGprsGsnAddres(map(a, 0)));

        // Huawei MSC / operator numbering / generic-vendor
        f.put("mtn_gmsc_supplServicesUsed", a -> VendorTransforms.mtn_gmsc_supplServicesUsed(map(a, 0)));
        f.put("normalization", a -> VendorTransforms.normalization(a.get(0)));
        f.put("selectWhere", a -> VendorTransforms.selectWhere(map(a, 0), str(a, 1), str(a, 2), str(a, 3), str(a, 4)));
        f.put("billablePulse", a -> a.get(0) instanceof BigInteger b
                ? VendorTransforms.billablePulse(b, str(a, 1))
                : VendorTransforms.billablePulse(str(a, 0), str(a, 1)));
        f.put("fetchValue", a -> switch (a.size()) {
            case 2 -> a.get(1) instanceof BigInteger b
                    ? tx.fetchValue(str(a, 0), b)
                    : tx.fetchValue(str(a, 0), str(a, 1));
            case 3 -> tx.fetchValue(str(a, 0), str(a, 1), str(a, 2));
            default -> throw new IllegalArgumentException("fetchValue arity " + a.size());
        });

        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(List<Object> args, int i) {
        return (Map<String, Object>) args.get(i);
    }

    private static String str(List<Object> args, int i) {
        return (String) args.get(i);
    }

    private static BigInteger bigInt(List<Object> args, int i) {
        return (BigInteger) args.get(i);
    }
}
