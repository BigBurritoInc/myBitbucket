package ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import domain.Participant
import domain.ReviewStatus
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.Icon

// Reviewer/author pictures are always this bundled generic icon — not fetched per-user (see
// CLAUDE.md "Avatars"). Who approved or requested changes is still shown, via getStatusIcon()'s
// overlay mark.
object ReviewerComponentFactory {
    val statusIconSize = JBUI.scale(20)
    val avatarSize = JBUI.scale(24) // HiDPI-scaled, not a raw pixel value.

    // Circular, like every other avatar in the IDE. createCircleImage() clips to a circle of
    // diameter min(width, height), and the source is square at avatarSize, so the circle is exactly
    // as wide as the square used to be — ReviewerItem's bounds, and the status mark sitting on top
    // of them, are unaffected.
    //
    // Deliberately not Image.getScaledInstance(): that hands back an image whose dimensions may not
    // be known yet, and ImageUtil.toBufferedImage() degenerates to a 1x1 placeholder for those.
    // ImageUtil.scaleImage() resolves synchronously.
    private val defaultAvatar: BufferedImage = ImageUtil.createCircleImage(
            ImageUtil.toBufferedImage(ImageUtil.scaleImage(resourceImage("avatar.png"), avatarSize, avatarSize)))

    val defaultAvatarIcon = JBImageIcon(defaultAvatar)

    fun getStatusIcon(participant: Participant): Icon? {
        return when (participant.status) {
            ReviewStatus.NEEDS_WORK -> AllIcons.General.BalloonWarning
            ReviewStatus.APPROVED -> AllIcons.General.InspectionsOK
            else -> null
        }
    }

    private fun resourceImage(relativePath: String): BufferedImage =
            ImageIO.read(javaClass.classLoader.getResource(relativePath))
}
