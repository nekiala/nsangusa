import { proxyApi } from "@/lib/runtime-api-proxy";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const GET = proxyApi;
export const HEAD = proxyApi;
export const POST = proxyApi;
export const PUT = proxyApi;
export const PATCH = proxyApi;
export const DELETE = proxyApi;
export const OPTIONS = proxyApi;
