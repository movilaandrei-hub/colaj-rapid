package ro.colajrapid.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Boomly Pro: a one-time Google Play purchase (product "boomly_pro") that unlocks everything.
 * Google Play is the source of truth; the last known state is cached so the app opens as Pro offline.
 * Purchases are acknowledged here, otherwise Google refunds them after 3 days.
 */
@CapacitorPlugin(name = "Billing")
public class BillingPlugin extends Plugin {

    static final String PRODUCT = "boomly_pro";
    private static final String PREFS = "boomly_billing";

    private BillingClient client;
    private ProductDetails product;
    private PluginCall buyCall;
    private final List<Runnable> waiting = new ArrayList<>();
    private boolean connecting = false;

    @Override
    public void load() {
        client = BillingClient.newBuilder(getContext())
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build();
    }

    @Override
    protected void handleOnDestroy() {
        if (client != null) client.endConnection();
    }

    /** Runs the task once Google Play is connected; on failure the task runs anyway and sees isReady() == false. */
    private synchronized void whenReady(Runnable task) {
        if (client.isReady()) { task.run(); return; }
        waiting.add(task);
        if (connecting) return;
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) { flush(); }

            @Override
            public void onBillingServiceDisconnected() { flush(); }
        });
    }

    private synchronized void flush() {
        connecting = false;
        List<Runnable> tasks = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : tasks) r.run();
    }

    private SharedPreferences prefs() { return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private JSObject state(boolean pro, boolean pending, boolean fromCache) {
        JSObject o = new JSObject();
        o.put("pro", pro);
        o.put("pending", pending);
        o.put("cached", fromCache);
        return o;
    }

    /** Is Pro bought on this Google account? Also acknowledges purchases that are not acknowledged yet. */
    @PluginMethod
    public void getStatus(PluginCall call) {
        whenReady(() -> {
            if (!client.isReady()) {
                call.resolve(state(prefs().getBoolean("pro", false), false, true));
                return;
            }
            client.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                    (result, purchases) -> {
                        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            call.resolve(state(prefs().getBoolean("pro", false), false, true));
                            return;
                        }
                        call.resolve(apply(purchases));
                    });
        });
    }

    /** Reads the purchase list, acknowledges, caches and returns the Pro state. */
    private JSObject apply(List<Purchase> purchases) {
        boolean pro = false, pending = false;
        if (purchases != null) for (Purchase p : purchases) {
            if (!p.getProducts().contains(PRODUCT)) continue;
            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                pro = true;
                if (!p.isAcknowledged()) client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(), r -> {});
            } else if (p.getPurchaseState() == Purchase.PurchaseState.PENDING) pending = true;
        }
        prefs().edit().putBoolean("pro", pro).apply();
        return state(pro, pending, false);
    }

    /** The localized price from Google Play, e.g. "19,99 RON". */
    @PluginMethod
    public void getProduct(PluginCall call) {
        whenReady(() -> loadProduct(pd -> {
            if (pd == null) { call.reject("Produsul nu e disponibil"); return; }
            JSObject o = new JSObject();
            ProductDetails.OneTimePurchaseOfferDetails offer = pd.getOneTimePurchaseOfferDetails();
            o.put("price", offer != null ? offer.getFormattedPrice() : "");
            o.put("title", pd.getName());
            call.resolve(o);
        }));
    }

    private interface ProductCb { void done(ProductDetails pd); }

    private void loadProduct(ProductCb cb) {
        if (product != null) { cb.done(product); return; }
        if (!client.isReady()) { cb.done(null); return; }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()))
                .build();
        client.queryProductDetailsAsync(params, (result, details) -> {
            List<ProductDetails> list = details.getProductDetailsList();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && !list.isEmpty()) product = list.get(0);
            cb.done(product);
        });
    }

    /** Opens the Google Play payment sheet. Resolves with the new state once the sheet closes. */
    @PluginMethod
    public void buy(PluginCall call) {
        whenReady(() -> loadProduct(pd -> {
            if (pd == null) { call.reject("Google Play nu e disponibil acum. Verifică internetul și încearcă din nou."); return; }
            if (buyCall != null) buyCall.reject("Înlocuit");
            buyCall = call;
            BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build()))
                    .build();
            getActivity().runOnUiThread(() -> {
                BillingResult r = client.launchBillingFlow(getActivity(), flow);
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) finishBuy(r, null);
            });
        }));
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        finishBuy(result, purchases);
    }

    private void finishBuy(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        JSObject s;
        if (code == BillingClient.BillingResponseCode.OK) s = apply(purchases);
        else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            prefs().edit().putBoolean("pro", true).apply();
            s = state(true, false, false);
        } else {
            s = state(prefs().getBoolean("pro", false), false, true);
            s.put("cancelled", code == BillingClient.BillingResponseCode.USER_CANCELED);
            s.put("error", code == BillingClient.BillingResponseCode.USER_CANCELED ? "" : result.getDebugMessage());
        }
        // a pending payment (e.g. cash at a shop) may complete later, while no buy() call is waiting
        notifyListeners("change", s);
        if (buyCall != null) { buyCall.resolve(s); buyCall = null; }
    }
}
