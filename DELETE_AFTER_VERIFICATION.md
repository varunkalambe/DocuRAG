# Files that are verification/documentation only

## Safe to delete after verification

```text
backend/scripts/
STAGES_1_72_COMPLETE_SOURCE.md
STAGES_1_72_MANIFEST.md
DELETE_AFTER_VERIFICATION.md
VERSION.txt
```

## Usually keep in a real repository

```text
README.md
.gitignore
backend/.gitignore
frontend/.gitignore
```

## Runtime/application code — do not delete

```text
backend/app/
frontend/src/
backend/requirements.txt
backend/.env.example
frontend/package.json
frontend/angular.json
frontend/tsconfig.json
frontend/tsconfig.app.json
frontend/tsconfig.spec.json  # only required by the Angular test target
```

Do not commit or package secrets or generated dependencies:

```text
backend/.env
backend/.venv/
frontend/node_modules/
frontend/dist/
__pycache__/
```
