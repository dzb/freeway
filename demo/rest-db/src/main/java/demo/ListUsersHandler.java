package demo;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.annotation.Inject;

/** GET /api/users — every stored user as a JSON array. */
public final class ListUsersHandler implements RouteHandler {

    private final UserService users;

    @Inject
    public ListUsersHandler(UserService users) {
        this.users = users;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        ctx.sendJson(200, users.list());
    }
}
