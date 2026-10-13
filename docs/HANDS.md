# Hands: Ustaad carries out a task on the phone

State: built and unit-tested; the ways in and two tasks that need no other app ran on one phone (a Redmi on Android 15); no task that acts in another app has run on a phone yet, because the accessibility switch is the owner's to flip. Numbers and their source: docs/benchmarks/2026-10-13-redmi-hands.md. Plan and safety rules: plans/2026-10-13-assistant-harness.md. Reviews: docs/review/2026-10-13-hands-codex-1.txt and -2.txt.

## What it is

The owner types or speaks a task ("add a litre of milk to my Blinkit cart", "write a polite message to move the meeting"). Ustaad plans it as a few subtasks, gives each to an expert, and carries them out:

- a **phone** subtask is done in an app: Ustaad reads the screen through its own accessibility service, a model picks one action at a time (tap, type, scroll, back, open app, open link, ask the person, done), a guard checks the action, the service performs it;
- a **knowledge** subtask is answered by an expert prompt (food choice, writing, shopping, travel, cooking, tech help, study, money basics);
- a **data** subtask asks the Data Union (the owner's indexed files);
- a **reply** subtask puts the outcomes together.

Experts for apps are short skill cards per app category (food delivery, quick commerce, shopping, rides, messaging, maps, video, browser, clock, phone), in `app/app/src/main/assets/hands/skills/`. Knowledge experts are prompts in `assets/hands/experts/`. The code has no router between different expert models: the same model answers under different expert prompts.

Two model tiers. The step model decides each action; the planning model plans and takes over a stuck subtask. On a phone with its own chat model both are that model. A stronger device of the owner's cluster is used for planning and for expert questions when one is there. Screen text stays on the phone unless the owner turns on "Let my other devices read the screen during a task".

## The ways in

| Way | What it does | Starts by itself |
|---|---|---|
| The Hands tab, or the "Ask Ustaad" shortcut on the launcher icon | task field, microphone, Run | on the owner's Run |
| Ustaad as the phone's digital assistant (Settings, Default apps, Digital assistant app) | the assistant gesture opens the task sheet over the current app | on the owner's Run |
| Share text to Ustaad, or select text and pick "Ask Ustaad" | the text is shown in the sheet with a note that it came from another app | never; the owner reads it and taps Run |
| The link `ustaad://do?q=...` (a Bixby Routine, any automation app, a web page) | same | never |

What does not exist, and why:
- Google Assistant and Gemini cannot be given custom commands for an app that is not on Google Play (App Actions are registered through a Play upload). They can open the app; the owner speaks inside it.
- Android 16 App Functions (the way Gemini calls functions of apps) are not built: alpha library, compileSdk 37, and the caller permission is privileged on most builds.
- There is no wake word: the platform reserves it for bundled assistants.
- Bixby has no public way for a third-party app to receive a task. On Samsung phones the paths above apply.
- Siri: there is no iOS app. The bridge would be an iOS Shortcut that posts the task to a device of the cluster; it needs the web gateway of the closed-cloud plan and is not built.
- An endpoint that lets another agent on the phone call these tools (MCP), and a driver that acts through a root shell instead of the accessibility service, are not built: the safety layer of the build session stopped both. The contract has room for them (`Hands.tools()`, `Hands.call()`, `PhoneDriver`).

## What the owner turns on

1. Settings, Accessibility, Ustaad Hands. On Android 13 and later a sideloaded app may first need "Allow restricted settings" in its App info menu. Ustaad never turns this on by itself.
2. Optional: Ustaad as the digital assistant.
3. A model: this phone's own, or a stronger device in the cluster. A phone too small for a model (the capability profile says so at setup) can only run phone subtasks with the sharing switch on, because the steps then have to be decided on another device.

## What it will not do, and how that is enforced

The guard does not depend on the model. It judges every action by the full text of the control (its own text, its children, the clickable parent a touch would reach, the words of its resource id), normalised against look-alike characters, split words and several languages.

- **Asks first, every time**: anything recognised as paying, ordering, buying, booking, sending, posting, deleting, installing, subscribing, signing in or accepting; every action on a screen with payment context; a button that shows only a price; a control with no text; every link, with the link and the opening app shown; an action in an app the plan did not name. The question appears in Ustaad's own window; "Do it" works only after the window has been visible for 0.7 seconds, and only a tap answers it. A timeout, Back or closing it is a no. A yes covers one reason and one control.
- **Careful mode (the default)**: on top of that, a tap is free only on a control known to be harmless (Add, Search, View cart, Skip, Close, an edit field and the like). Every other tap and every Enter is asked about. In the test app a search, open, add, view-cart run asks twice.
- **Fewer questions (a switch)**: taps are asked about only when recognised as committing. The recognition is a word list and can miss a button; the switch says so.
- **Never**: typing into a password field or a field that asks for a password, PIN, one-time code, CVV or card number; typing a card number anywhere; acting in banking, UPI and wallet apps, password managers, authenticators, permission dialogs, installers, the root manager or Settings; reading such screens at all (they are given to the model as "protected"); acting in Ustaad's own window (so it cannot answer its own question).
- Before every action the control is found again in a fresh read; if it is gone, changed or no longer unique, nothing is done. The service clicks exactly that control (or the clickable parent the guard already judged), compares its whole subtree again at that moment, and uses no gestures and no coordinates.
- A Stop chip is on screen while a task runs, and a Stop action in the notification.
- A task from another app never starts without the owner's Run. Other phones of the cluster cannot start a task here.
- Each run leaves one audit record with counts only (steps, verdicts, duration, final phase).

## What it relies on, and what remains

- It trusts the apps a task names to label their own controls honestly. It does not trust content shown inside them, and it does not trust the model.
- A password field that is neither flagged by the app nor labelled, and a code shown as a bare number on a screen with no word for it, cannot be recognised.
- An app that mislabels its own controls can still be made to commit in Fewer questions mode and, if it uses a harmless-list word for a committing control, in Careful mode.
- On Android 16 apps can hide marked views from a service like this one; such screens read as empty.
- Google Play forbids apps that use the accessibility API to plan and act by themselves. Hands is for sideloaded builds.
- Reliability with the 4B model, measured on the PC against a test app (not a phone, not a real app): a single-item cart task succeeded in 5 of 5 and 4 of 5 runs; harder tasks (two items, an item that does not exist, an expert first) succeeded in 0 to 2 of 5. About 6 to 10 seconds per step with the model on a laptop GPU. No run tapped a pay or order control.

## Where the code is

- Contract: `hands/Contract.kt`. Core, no Android in it: `hands/` (ScreenReader, Guard, GuardText, GuardWords, HandsTools, ToolRunner, StepLoop, Planner, HandsEngine, Skills, UiDump).
- Android side: `hands/android/` (accessibility service and driver, app catalogue, Stop chip and notification, brain and its route rule, expert desk, runtime, settings), `assist/` (assistant services, task sheet, the person's prompt), `hands/ui/` (the Hands tab).
- Tests: `app/app/src/test/java/com/swarmos/app/hands` and `.../assist`. The opt-in evaluation with a real model is `HandsEvalTest` (set HANDS_EVAL_URL).
