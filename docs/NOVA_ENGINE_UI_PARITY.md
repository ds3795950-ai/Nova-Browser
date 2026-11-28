# Nova engine UI parity

Nova owns the browser interface; rendering engines own page rendering and engine resources only.

## Required parity

- Both engines mount beneath the same `MainActivity` browser chrome.
- Address/search fields, toolbar surfaces, status labels, spacing, touch targets, typography and theme colors use `NovaBrowserChromeStyle`.
- Primary and split-pane address fields share the same input type, keyboard action, sizing and visual treatment.
- Engine changes must not replace, restyle or rearrange Nova's toolbar, home surface, tab controls, menus or navigation affordances.
- User-facing engine choices use Nova product language (`Nova Core` and `Nova Compatibility`); SDK names remain implementation details.

## Allowed differences

- Website content and rendering behavior can differ because the underlying engines are different.
- Engine-specific capabilities may be unavailable in one engine. Nova should explain the limitation in the relevant action rather than hiding or restyling the whole browser interface.
- Loading and error details may vary when the underlying engine reports different states, but they must use the same Nova status area and typography.

## Validation

`NovaBrowserChromeStyleTest` locks the shared chrome metrics across both engine types. Android CI and emulator smoke tests validate that both rendering views can be hosted under the same chrome after the refactor.
