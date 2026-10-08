package mlabeler.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities

actual object ScreenColor {
    actual val supported: Boolean = !GraphicsEnvironment.isHeadless()

    actual fun pick(onPicked: (Int?) -> Unit) {
        SwingUtilities.invokeLater {
            val result = runCatching { open(onPicked) }
            if (result.isFailure) onPicked(null)
        }
    }

    private fun open(onPicked: (Int?) -> Unit) {
        // the whole desktop (all monitors) as it is now; the window shows this picture, so what is under the
        // pointer is exactly the pixel that will be taken
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }.reduce { a, b -> a.union(b) }
        val shot: BufferedImage = Robot().createScreenCapture(Rectangle(bounds))
        val window = JWindow()
        var mouse: Point? = null
        var done = false
        fun finish(color: Int?) {
            if (done) return
            done = true
            window.isVisible = false
            window.dispose()
            onPicked(color)
        }
        fun colorAt(p: Point): Int {
            val x = (p.x * shot.width / bounds.width).coerceIn(0, shot.width - 1)
            val y = (p.y * shot.height / bounds.height).coerceIn(0, shot.height - 1)
            return shot.getRGB(x, y)
        }
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.drawImage(shot, 0, 0, bounds.width, bounds.height, null)
                val p = mouse ?: return
                // a small magnifier next to the pointer with the colour under it
                val zoom = 8
                val half = 7
                val size = (half * 2 + 1) * zoom
                val ox = (p.x + 20).let { if (it + size > bounds.width) p.x - 20 - size else it }
                val oy = (p.y + 20).let { if (it + size + 22 > bounds.height) p.y - 20 - size - 22 else it }
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                val sx = p.x * shot.width / bounds.width
                val sy = p.y * shot.height / bounds.height
                g2.drawImage(shot, ox, oy, ox + size, oy + size, sx - half, sy - half, sx + half + 1, sy + half + 1, null)
                g2.color = Color.BLACK
                g2.stroke = BasicStroke(1f)
                g2.drawRect(ox, oy, size, size)
                g2.drawRect(ox + half * zoom, oy + half * zoom, zoom, zoom)
                val c = Color(colorAt(p))
                g2.color = c
                g2.fillRect(ox, oy + size, size + 1, 22)
                g2.color = if (c.red * 0.3 + c.green * 0.59 + c.blue * 0.11 > 128) Color.BLACK else Color.WHITE
                g2.drawString(String.format("#%02x%02x%02x", c.red, c.green, c.blue), ox + 6, oy + size + 15)
            }
        }
        panel.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        val mouseHandler = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { mouse = e.point; panel.repaint() }
            override fun mouseDragged(e: MouseEvent) { mouse = e.point; panel.repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) finish(colorAt(e.point)) else finish(null)
            }
        }
        panel.addMouseListener(mouseHandler)
        panel.addMouseMotionListener(mouseHandler)
        panel.isFocusable = true
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ESCAPE) finish(null) }
        })
        window.contentPane = panel
        window.bounds = bounds
        window.isAlwaysOnTop = true
        window.isVisible = true
        window.toFront()
        panel.requestFocusInWindow()
    }
}
