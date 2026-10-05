import { test } from "./english";
import { securityHeaderJourney } from "./security-headers-helpers";

test("production nonce headers preserve hydration and navigation while blocking untrusted scripts", async ({ page }) => {
  await securityHeaderJourney(page);
});
