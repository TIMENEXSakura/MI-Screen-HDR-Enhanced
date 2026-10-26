import os
from pathlib import Path
import unittest
import patch_shader as patch


class PatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(os.environ.get('HDR_SF_LIBRARY', '/system_ext/lib64/libsurfaceflinger.so'))
        if not path.is_file():
            raise unittest.SkipTest('supply the audited fixture via HDR_SF_LIBRARY')
        cls.data = path.read_bytes()
        patch.inspect(cls.data)

    def test_changes_only_shader_without_moving_terminator(self):
        after, report = patch.transform(self.data)
        self.assertEqual(len(after), len(self.data))
        self.assertEqual(after[:patch.OFFSET], self.data[:patch.OFFSET])
        self.assertEqual(after[patch.OFFSET+patch.LENGTH:], self.data[patch.OFFSET+patch.LENGTH:])
        self.assertFalse(report['installed'])

    def test_restore_byte_for_byte(self):
        after, _ = patch.transform(self.data)
        restored, _ = patch.transform(after, restore=True)
        self.assertEqual(restored, self.data)

    def test_repeated_patch_is_idempotent(self):
        after, _ = patch.transform(self.data)
        again, _ = patch.transform(after)
        self.assertEqual(after, again)

    def test_unrelated_library_change_rejected(self):
        after = bytearray(self.data)
        after[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'fingerprint'):
            patch.transform(bytes(after))

    def test_shader_and_terminator_change_rejected(self):
        for offset in (patch.OFFSET, patch.OFFSET+patch.LENGTH):
            after = bytearray(self.data)
            after[offset] ^= 1
            with self.assertRaises(ValueError):
                patch.transform(bytes(after))

    def test_wrong_architecture_rejected(self):
        after = bytearray(self.data)
        after[18] = 62
        with self.assertRaisesRegex(ValueError, 'AArch64'):
            patch.transform(bytes(after))

    def test_truncated_input_rejected(self):
        for length in (0, 64, patch.OFFSET+patch.LENGTH):
            with self.assertRaises(ValueError):
                patch.transform(self.data[:length])

if __name__ == '__main__':
    unittest.main()
