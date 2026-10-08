package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.discovery.CloudDiscoveryModule;
import com.jujin.freeway.cloud.discovery.RegistryServerModule;
import com.jujin.freeway.http.HttpModule;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone node: a freeway process whose job is to BE the registry server.
 *
 * <p>It starts with a local {@code RegistryStore} and exposes it over HTTP;
 * nothing else registers into it — if a second freeway app hits its
 * endpoints, it finds them. Read {@code README.md} for the walk-through.
 */
public final class RegistryServer {

    public static void main(String[] args) throws InterruptedException {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "19090");

        FreewayApp.run(args,
            new HttpModule(),
            new CloudDiscoveryModule(),
            new RegistryServerModule());

        System.out.println("Registry server listening on http://127.0.0.1:19090");
        System.out.println("  register : curl -X POST localhost:19090/registry/services/svc/instances \\");
        System.out.println("      -d '<service-instance-json>'");
        System.out.println("  discover : curl localhost:19090/registry/services/svc/instances");
        System.out.println("  renew    : curl -X POST localhost:19090/registry/services/svc/instances/i1/renew");
        System.out.println("  delete   : curl -X DELETE localhost:19090/registry/services/svc/instances/i1");
        System.out.println("[registry-server] ready — listening until killed");
        new CountDownLatch(1).await(); // resident registry server
    }
}
