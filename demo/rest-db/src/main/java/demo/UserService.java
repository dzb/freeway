package demo;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.Orm;
import com.jujin.freeway.ioc.annotation.Inject;

import java.util.List;
import java.util.Optional;

/**
 * The service: {@link Orm} and {@link Database} arrive as constructor
 * arguments — both are bound by {@code DbModule}. Handler classes depend on
 * this type, so the module binds it.
 */
public final class UserService {

    private final Orm orm;
    private final Database db;

    @Inject
    public UserService(Orm orm, Database db) {
        this.orm = orm;
        this.db = db;
    }

    public List<User> list() {
        return orm.findAll(User.class);
    }

    public Optional<User> find(long id) {
        return orm.findById(User.class, id);
    }

    /**
     * Insert in a transaction; return the stored row with its generated id.
     * {@code transaction} is thread-bound to this database — misuse fails
     * loudly rather than silently escaping it.
     */
    public User create(CreateUser command) {
        long[] id = new long[1];
        db.transaction(() ->
            id[0] = orm.insert(new User(command.name(), command.age())).longKey());
        return orm.findById(User.class, id[0]).orElseThrow();
    }
}
