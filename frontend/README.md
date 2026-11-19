# Debrid Media Downloader frontend

The frontend uses [Angular CLI](https://github.com/angular/angular-cli) 21.2.30 and [Optimus UI](https://github.com/openng-org/optimus-ui) 1 with Optimus UI Themes and OpenNG Icons, all MIT licensed. Optimus UI 1 supports Angular 21; upgrade its major version together with Angular. Use Node.js 24.15.0+ in the 24.x line and npm 12.2.2. Run `npm ci` before starting development; see the root README for backend and Docker setup.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
