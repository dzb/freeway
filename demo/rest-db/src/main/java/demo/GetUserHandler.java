package demo;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.annotation.Inject;

/** GET /api/users/{id} — one user, or 404 when the id is not stored. */
public final class GetUserHandler implements RouteHandler {

    private final UserService users;

    @Inject
    public GetUserHandler(UserService users) {
        this.users = users;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        // Coerced to long: a non-numeric id fails the request, not find().
        long id = ctx.pathVar("id", Long.class).orElseThrow();
        var user = users.find(id);
        if (user.isEmpty()) {
            ctx.send(404, "Not Found");
            return;
        }
        ctx.sendJson(200, user.orElseThrow());
    }
}
