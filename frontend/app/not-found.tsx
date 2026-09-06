import Link from "next/link";
export default function NotFound() { return <section className="empty shell"><p className="eyebrow">404</p><h1>Nothing here.</h1><p>The address may have changed, or the story has not been published. <Link href="/">Return home.</Link></p></section>; }
