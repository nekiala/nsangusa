# Nsangusa frontend

An editorial Next.js 16 App Router frontend with server-rendered public publishing pages and a typed Spring Boot API client.

## API configuration

Copy `.env.example` to `.env.local`. The default `NEXT_PUBLIC_API_MODE=api` calls the implemented
Spring Boot routes at `NEXT_PUBLIC_API_URL`; `NSANGUSA_API_URL` is used for server-rendered public
requests. Browser requests use `credentials: "include"` for the opaque Spring session cookie. No
tokens are persisted in `localStorage`.

Mutations bootstrap `/api/v1/auth/csrf` and send its returned header/token pair on the same request
credentials. Auth, admin, and mutation requests are `no-store`; public article requests use a short,
safe revalidation window. Configure the API and frontend as same-site (or configure backend CORS and
cookie policy) so session cookies can be sent.

Set `NEXT_PUBLIC_API_MODE=fake` explicitly for local visual demos or tests without a backend. Fake
mode implements only the documented client workflow in memory; it is not selected automatically.
The admin UI intentionally has no invented queues or list endpoints: the backend currently exposes
individual article lookup, editorial transitions, comment moderation by ID, X-account actions, and
an operations summary.

## Commands

```bash
npm install
npm run dev
npm run lint
npm test
npm run build
npm run start
npm run test:e2e
```

Public pages include an RSS feed at `/rss.xml`, `sitemap.xml`, and `robots.txt`. Admin, profile, and account paths deliberately send private/no-store cache headers. Playwright starts the development server in explicit fake mode automatically; install browsers if needed with `npx playwright install chromium`.

## Container

```bash
docker build -t nsangusa-frontend .
docker run -p 3000:3000 nsangusa-frontend
```
