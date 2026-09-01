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

// docs: Avatars
object ReviewerComponentFactory {
    val statusIconSize = JBUI.scale(20)
    val avatarSize = JBUI.scale(24) // HiDPI-scaled, not a raw pixel value.

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
