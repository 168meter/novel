# Header Long Username Display Design

## Problem

After login, `static/javascript/common.js` replaces the header login/register links with the complete nickname. Newly registered accounts receive a nickname such as `读者<雪花 ID>`, which can be much wider than the available header area. The generated anchor does not use the existing username class, while `.bookShelf` clips overflow, so the visible user controls may disappear.

## Scope

- Change only the shared desktop header rendering and its responsive presentation.
- Preserve the complete nickname in application state and in the link `title` attribute.
- Keep the bookshelf and logout actions visible.
- Do not change authentication, registration, database values, API contracts, or mobile-only pages.

## Design

Create the authenticated header DOM with jQuery nodes instead of HTML string concatenation. Select the display name from `nickName`, falling back to `username` and then an empty string. Set the username anchor with `.text(displayName)` and `.attr("title", displayName)` so user-controlled text is not interpreted as HTML.

Add a dedicated `header_user_name` class. It is an inline block with a desktop maximum width, `overflow: hidden`, `text-overflow: ellipsis`, and `white-space: nowrap`. A narrow-screen media query uses a smaller maximum width. The surrounding logout link remains a separate, non-shrinking action.

## Verification

A classpath resource contract test will verify:

- the script creates the dedicated username link and uses text/title setters;
- no nickname is concatenated into an HTML string;
- CSS contains the single-line ellipsis contract and a narrower responsive rule;
- the logout action remains independently rendered.

