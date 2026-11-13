Denmark Road Assistant - thesis evidence
=========================================

Double-click RUN_ME.bat. It takes about 10 to 15 minutes. Leave the window open.

What it does, in order:
 1. Runs the backend tests (mvnw test), the ML service tests (pytest) and the
    frontend tests (ng test), and builds the frontend for production.
 2. Starts the ML service, the backend and the frontend.
 3. Opens the app in a hidden Microsoft Edge window and uses it like a driver:
    analyses routes, signs up a test user, logs in, saves feedback, edits the
    profile and follows turn-by-turn guidance with a simulated position.
    It takes the screenshots for the thesis and records each system test case.
 4. Checks every page for accessibility problems (axe-core) and times the
    ML service.
 5. Stops the ML service on purpose to check the error message, then stops
    everything.

Your own accounts and saved analyses are not touched: the backend runs against
a separate test database (backend\data\thesis-evidence.*) that is deleted at
the end. Results go into the "output" folder next to this file.
