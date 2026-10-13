package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlderAndroidPhotosTest {
    private val root = "/storage/emulated/0"

    // ---- the folder of an image: RELATIVE_PATH from Android 10, the cut file path (DATA) before it ------------------------

    @Test fun onAndroid10AndUpTheFolderIsRelativePathAsItWasBefore() {
        assertEquals("Pictures/SwarmDemo", PduIndexer.photoFolderOf("Pictures/SwarmDemo/", relative = true, storageRoot = root))
        assertEquals("DCIM/Camera", PduIndexer.photoFolderOf("DCIM/Camera/", relative = true, storageRoot = root))
        assertEquals("a missing value was and stays Pictures", "Pictures", PduIndexer.photoFolderOf(null, relative = true, storageRoot = root))
        assertEquals("Pictures/UstaadDemo%", PduIndexer.photoFolderLike("Pictures/UstaadDemo/", relative = true, storageRoot = root))
        assertEquals("Pictures/UstaadDemo%", PduIndexer.photoFolderLike("Pictures/UstaadDemo", relative = true, storageRoot = root))
    }

    @Test fun onAndroid8And9TheFolderIsTheDirectoryOfTheFilePathBelowTheStorageRoot() {
        assertEquals("Pictures/SwarmDemo", PduIndexer.photoFolderOf("$root/Pictures/SwarmDemo/IMG_1046.jpg", relative = false, storageRoot = root))
        assertEquals("DCIM/Camera", PduIndexer.photoFolderOf("$root/DCIM/Camera/IMG_1.jpg", relative = false, storageRoot = "$root/"))
        assertEquals("a file straight in the root has no folder", "", PduIndexer.photoFolderOf("$root/x.jpg", relative = false, storageRoot = root))
        assertEquals("a missing value is Pictures", "Pictures", PduIndexer.photoFolderOf(null, relative = false, storageRoot = root))
        assertEquals(
            "a card outside the primary storage keeps its whole directory",
            "storage/1234-ABCD/DCIM", PduIndexer.photoFolderOf("/storage/1234-ABCD/DCIM/a.jpg", relative = false, storageRoot = root),
        )
    }

    @Test fun theLikeArgumentMatchesTheSameImagesInBothColumns() {
        assertEquals("$root/Pictures/UstaadDemo%", PduIndexer.photoFolderLike("Pictures/UstaadDemo/", relative = false, storageRoot = root))
        assertEquals("$root/Pictures/UstaadDemo%", PduIndexer.photoFolderLike("Pictures/UstaadDemo", relative = false, storageRoot = "$root/"))
        // and the folder found from a file under that prefix agrees with the prefix the index root filters on
        val folder = PduIndexer.photoFolderOf("$root/Pictures/UstaadDemo/a.jpg", relative = false, storageRoot = root)
        assertTrue(folder.startsWith("Pictures/UstaadDemo"))
    }

    // ---- decoding an image on Android 8 (no ImageDecoder) -------------------------------------------------------------------

    @Test fun theExifOrientationBecomesARotationAndAMirror() {
        val turns = (1..8).map { ImageTurn.forExif(it) }
        assertEquals(
            listOf(
                ImageTurn.Turn(0, false), ImageTurn.Turn(0, true), ImageTurn.Turn(180, false), ImageTurn.Turn(180, true),
                ImageTurn.Turn(90, true), ImageTurn.Turn(90, false), ImageTurn.Turn(270, true), ImageTurn.Turn(270, false),
            ),
            turns,
        )
        assertTrue(turns[0].isNone)
        assertTrue("undefined (0) and unknown values are no turn", ImageTurn.forExif(0).isNone && ImageTurn.forExif(9).isNone)
        assertFalse(turns[5].isNone)
    }

    @Test fun theSampleSizeIsThePowerOfTwoThatKeepsTheLongestSideAtTheTargetOrMore() {
        assertEquals("a 4000 x 3000 photo to 2400: 1, since 2000 would be below", 1, ImageTurn.sampleSize(4000, 3000, 2400))
        assertEquals(2, ImageTurn.sampleSize(4800, 3600, 2400))
        assertEquals(2, ImageTurn.sampleSize(3000, 4800, 2400))
        assertEquals("a 12 MP phone photo", 1, ImageTurn.sampleSize(4032, 3024, 2400))
        assertEquals("a 48 MP photo", 2, ImageTurn.sampleSize(8000, 6000, 2400))
        assertEquals("a small image is not sampled", 1, ImageTurn.sampleSize(800, 600, 2400))
        assertEquals("exactly twice the target", 2, ImageTurn.sampleSize(4800, 100, 2400))
        assertEquals("unknown size (the decoder gave -1)", 1, ImageTurn.sampleSize(-1, -1, 2400))
        assertEquals(1, ImageTurn.sampleSize(4000, 3000, 0))
    }
}
