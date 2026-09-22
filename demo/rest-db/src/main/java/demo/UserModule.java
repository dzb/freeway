package demo;

import com.jujin.freeway.db.schema.SchemaEntity;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

/**
 * The composition unit: bindings, table DDL and routes are all declared
 * here, and the entry point places this module whole.
 */
public final class UserModule implements ModuleEx {

    @Override
    public void bind(Binder binder) {
        // Handler classes are constructed by the container at startup
        // (fail-fast), so their dependency must be bound.
        binder.bind(UserService.class).to(UserService.class);

        // schema.auto (default on) creates t_user before HTTP starts;
        // a db/migration/ SQL file is the alternative — one per table.
        binder.contribute(SchemaEntity.class)
            .add(SchemaEntity.of("core", User.class));

        binder.contribute(Route.class)
            .add(Route.get("/api/users", ListUsersHandler.class))
            .add(Route.get("/api/users/{id}", GetUserHandler.class))
            .add(Route.post("/api/users", CreateUserHandler.class));
    }
}
