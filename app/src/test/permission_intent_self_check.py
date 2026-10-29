from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2] / "src/main/kotlin/dev/dsh/mobile/app/notify"

class PermissionIntentIdentity(unittest.TestCase):
    def test_answer_and_pick_have_unambiguous_identity(self):
        source = (ROOT / "OngoingCenter.kt").read_text(encoding="utf-8")
        for action, parts in (("ANSWER", ("waiting.sessionId", "perm.requestId", "opt.optionId")), ("PICK", ("pickable.sessionId", "opt.id", "opt.value"))):
            block = source.split(".setAction(NotificationActionReceiver.ACTION_" + action + ")", 1)[1].split("PendingIntent.FLAG_UPDATE_CURRENT", 1)[0]
            self.assertIn(".setData(", block)
            for part in parts:
                self.assertIn(".appendPath(" + part + ")", block)
            self.assertIn(".build()", block)

    def test_permission_navigation_has_full_identity(self):
        source = (ROOT / "NotificationCenter.kt").read_text(encoding="utf-8")
        block = source.split("val intent = Intent(context, MainActivity::class.java).apply {", 1)[1].split("val pi =", 1)[0]
        for part in ("sessionId", "requestId"):
            self.assertIn(".appendPath(" + part + ")", block)
        self.assertIn("Uri.Builder()", block)

if __name__ == "__main__":
    unittest.main()
