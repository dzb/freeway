package demo;

import com.jujin.freeway.commons.validation.BeanValidator;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.ValidationException;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.annotation.Inject;

/**
 * POST /api/users — deserialize, validate, persist, answer 201 with the
 * stored row (id included).
 */
public final class CreateUserHandler implements RouteHandler {

    private final UserService users;

    @Inject
    public CreateUserHandler(UserService users) {
        this.users = users;
    }

    @Override
    public void handle(HttpContext ctx) throws Exception {
        CreateUser body = ctx.bodyAsJson(CreateUser.class);
        var result = BeanValidator.validate(body);
        if (result.hasErrors()) {
            // The framework's error handlers map this to a 400 response.
            throw new ValidationException(result);
        }
        ctx.sendJson(201, users.create(body));
    }
}
