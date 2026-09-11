# Contributing

Contributions to cloud-itonami-isco-3121 are welcome. Please read and respect
the scope boundaries outlined in README.md. This repository maintains a strict
separation between supervisor administrative operations and operator/management
exclusive decisions.

## Before you PR

1. Check GOVERNANCE.md for decision process.
2. Ensure scope does not blur supervisor vs. operator boundaries.
3. Add tests for new features.
4. Run `kbb -M:test` and ensure all tests pass.

## Testing

```bash
kbb -M:test
```

## Code style

Follow existing patterns in the codebase. `.cljc` files are preferred for
portable Clojure(Script) code.
