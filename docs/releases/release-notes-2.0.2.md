# Freeway 2.0.2 Release Notes

> Version: 2.0.2 (2026-11-24) | Highlight: **TOML as a third configuration format, quieter logging, and weekly/monthly rotation.**

2.0.2 is a configuration-and-logging release. It adds a deliberately small TOML
reader to the configuration file family, makes SLF4J provider selection stop
narrating the default case, gives the startup banner its own switch, and adds
weekly and monthly log rotation. Two behavior changes are worth reading before
upgrading: a path ending in `.toml` is now parsed as TOML, and the default
size-rotation threshold is 256 MB.

## Highlights

### TOML as a third configuration format

`freeway-commons` and `freeway-boot` read a hand-written TOML parser for a
deliberately small, line-oriented subset: tables, arrays of tables, bare and
quoted keys, basic strings with escapes, decimal 64-bit integers, booleans and
single-line string arrays. Literal strings, multi-line strings, inline tables,
dotted keys, floats and date/time values are rejected and named as such; a
duplicate key, or a header colliding with a value, is an error (JSON's
last-one-wins does not apply).

`application.toml` joins the application file family in the last slot —
properties → json → toml, later winning — so a key it declares outranks the
same key in the earlier formats. Profile variants and `freeway.config.file`
extras accept it like the other formats. Parse failures carry the line and
column (`TomlParseException`, listed in the failure catalog).

### Quieter provider selection

Freeway only sets `slf4j.provider` when the backend was selected explicitly or
more than one SLF4J provider is on the classpath. With the default and a single
provider, SLF4J's own `Attempting to load provider ...` INFO line no longer
appears.

### `freeway.log.banner`

A class-load-time switch (`-D` or `FREEWAY_LOG_BANNER`, default `true`).
`false` silences the one-line `[Freeway] logging: ...` startup report so
libraries and CLIs can keep stderr clean; warnings and errors still print.

### Weekly and monthly rotation

`freeway.log.file.rotate` (and the named-file `.rotate`) accepts `daily`
(default), `weekly` or `monthly`. A weekly archive is named by its week's
Monday and a monthly one by the first of its month; retention still counts days
and is measured from the archive's last day (Sunday, month end), so no record is
dropped before `max-history` days. A restart within the same period skips
archive slots that already exist (raw or `.gz`) instead of overwriting them.

## Compatibility

- **A file named `*.toml` is parsed as TOML now** (`freeway-boot`): a path
  listed in `freeway.config.file` (or handed to `AppConfigDefault` as an
  override path) whose name ends with `.toml` was read as a
  `java.util.Properties` file before this release. A misnamed properties file
  has to be renamed; TOML syntax in such a path now decides whether startup
  proceeds.
- **The default size-rotation threshold is 256 MB, not 100 MB**
  (`freeway-commons` logging): `freeway.log.file.max-size` (and the named-file
  `.max-size`) now defaults to `268435456`. Daily rotation and the 30-day
  retention window are unchanged, so the disk bound (daily volume × 30) is
  unchanged; high-volume services simply keep fewer files per day.
- Java: 25+. No public API was removed.

## Verification

- `jeno lock` and `jeno build`: 7 modules.
- `jeno test`: **2,686 tests, 0 failures** across the 7 modules.
- The tag pipeline runs the same core gate, then the in-repo demos, the
  freeway-ext adapters and the documentation checks before it publishes.
- The release publishes the jeno repository to GitHub Pages
  (`https://dzb.github.io/freeway/`) and attaches the offline repository
  tarball to the GitHub release. Central remains the Maven channel, and its
  bytes may differ from the Pages repository.

The complete change list is in [CHANGELOG.md](../../CHANGELOG.md).
