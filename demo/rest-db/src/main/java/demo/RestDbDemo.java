package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.db.DbModule;
import com.jujin.freeway.http.HttpModule;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point: the application is exactly these three modules — the demo's
 * own {@link UserModule}, HTTP, and the database. Nothing else is on the
 * classpath, so nothing else can take part.
 *
 * <p>Runs until killed. See README.md for the curl walkthrough.</p>
 */
public final class RestDbDemo {

    public static void main(String[] args) throws InterruptedException {
        FreewayApp.run(new UserModule(), new HttpModule(), new DbModule());
        new CountDownLatch(1).await(); // resident demo process
    }
}
