// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.ui

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.asSafely
import com.intellij.util.text.nullize
import com.intellij.util.ui.html.*
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.*
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.SizeRequirements
import javax.swing.text.*
import javax.swing.text.Position.Bias
import javax.swing.text.html.*
import javax.swing.text.html.HTMLEditorKit.HTMLFactory
import javax.swing.text.html.ParagraphView
import kotlin.math.max

/**
 * Pluggable [HTMLFactory] which allows overriding and adding functionality to [HTMLFactory] without using inheritance
 */
class ExtendableHTMLViewFactory internal constructor(
  private val extensions: List<(Element, View) -> View?>,
  private val base: ViewFactory = HTMLEditorKit().viewFactory
) : HTMLFactory() {

  internal constructor(vararg extensions: (Element, View) -> View?) : this(extensions.asList())

  internal constructor(vararg extensions: Extension) : this(extensions.asList())

  override fun create(elem: Element): View {
    val defaultView = base.create(elem)
    for (extension in extensions) {
      val view = extension(elem, defaultView)
      if (view != null && view !== defaultView) return view
    }
    return defaultView
  }

  companion object {
    @JvmField
    val DEFAULT_EXTENSIONS: List<Extension> = listOf(
      Extensions.ICONS, Extensions.BASE64_IMAGES, Extensions.HIDPI_IMAGES,
      Extensions.INLINE_VIEW_EX, Extensions.WBR_SUPPORT, Extensions.PARAGRAPH_VIEW_EX,
      Extensions.BLOCK_VIEW_EX
    )

    @JvmField
    val DEFAULT: ExtendableHTMLViewFactory = ExtendableHTMLViewFactory(DEFAULT_EXTENSIONS)

    private val DEFAULT_EXTENSIONS_WORD_WRAP = DEFAULT_EXTENSIONS + Extensions.WORD_WRAP

    @JvmField
    val DEFAULT_WORD_WRAP: ExtendableHTMLViewFactory = ExtendableHTMLViewFactory(DEFAULT_EXTENSIONS_WORD_WRAP)
  }

  @FunctionalInterface
  interface Extension : (Element, View) -> View?

  /**
   * Collection of most popular features
   */
  object Extensions {

    /**
     * Render icons from the provided map
     *
     * Syntax is `<icon src='MAP_KEY'>`
     */
    @JvmStatic
    fun icons(preLoaded: Map<String, Icon> = emptyMap()): Extension = if (preLoaded.isEmpty()) ICONS else IconsExtension(preLoaded)

    /**
     * Render icons provided by [existingIconsProvider]
     *
     * Syntax is `<icon src='KEY'>`
     */
    @JvmStatic
    fun icons(existingIconsProvider: (key: String) -> Icon?): Extension = IconsExtension(existingIconsProvider)

    /**
     * Render icons provided by [htmlChunk]
     *
     * Syntax is `<icon src='KEY'>`
     */
    @JvmStatic
    fun icons(htmlChunk: HtmlChunk): Extension = IconsExtension { htmlChunk.findIcon(it) }

    /**
     * Render icons from IJ icon classes
     *
     * Syntax is `<icon src='FQN_FOR_ICON'>`
     */
    @JvmField
    val ICONS: Extension = IconsExtension(emptyMap())

    /**
     * Render base64 encoded images
     *
     * Syntax is `<img src='data:image/png;base64,ENCODED_IMAGE_HERE'>`
     */
    @JvmField
    val BASE64_IMAGES: Extension = Base64ImagesExtension()

    /**
     * Wrap words that are too long, for example: A_TEST_TABLE_SINGLE_ROW_UPDATE_AUTOCOMMIT_A_FIK
     */
    @JvmField
    val WORD_WRAP: Extension = WordWrapExtension()

    /**
     * Supports rendering of inline elements, like <span>, with paddings, margins
     * and rounded corners (through `caption-side` CSS property).
     */
    @JvmField
    val INLINE_VIEW_EX: Extension = InlineViewExExtension()

    /**
     * Supports rendering of block elements, like <div>,
     * with rounded corners (through `caption-side` CSS property).
     */
    @JvmField
    val BLOCK_VIEW_EX: Extension = BlockViewExExtension()

    /**
     * Supports line-height property (%, px and no-unit) in paragraphs.
     */
    @JvmField
    val PARAGRAPH_VIEW_EX: Extension = ParagraphViewExExtension()

    /**
     * Renders images with proper scaling according to sysScale
     */
    @JvmField
    val HIDPI_IMAGES: Extension = HiDpiImagesExtension()

    /**
     * Renders images in a fit-to-width manner.
     *
     * Too large image will not cause HTML editor pane to resize,
     * but will be scaled down to fit the editor's width.
     */
    @JvmField
    val FIT_TO_WIDTH_IMAGES: Extension = FitToWidthImageViewExtension()

    /**
     * Adds support for `<wbr>` tags
     */
    @JvmField
    val WBR_SUPPORT: Extension = WbrSupportExtension()

    private class IconsExtension(private val existingIconsProvider: (key: String) -> Icon?) : Extension {

      constructor(preloadedIcons: Map<String, Icon>) : this(preloadedIconsProvider(preloadedIcons))

      override fun invoke(elem: Element, defaultView: View): View? {
        if (StyleConstants.IconElementName != elem.name) return null
        val src = elem.attributes.getAttribute(HTML.Attribute.SRC) as? String ?: return null
        val icon = getIcon(src) ?: return null
        return JBIconView(elem, icon)
      }

      private fun getIcon(src: String): Icon? {
        val existingIcon = existingIconsProvider(src)
        if (existingIcon != null) {
          return existingIcon
        }
        return IconLoader.findIcon(path = src, aClass = ExtendableHTMLViewFactory::class.java, deferUrlResolve = true, strict = false)
      }

      /**
       * @see [javax.swing.text.IconView]
       */
      private class JBIconView(elem: Element, private val icon: Icon) : View(elem) {

        override fun getPreferredSpan(axis: Int): Float {
          return when (axis) {
            X_AXIS -> icon.iconWidth.toFloat()
            Y_AXIS -> icon.iconHeight.toFloat()
            else -> throw IllegalArgumentException("Invalid axis: $axis")
          }
        }

        override fun getAlignment(axis: Int): Float {
          // 12 is a "standard" font height which has user scale of 1
          if (axis == Y_AXIS) return JBUIScale.scale(12) / icon.iconHeight.toFloat()
          return super.getAlignment(axis)
        }

        override fun getToolTipText(x: Float, y: Float, allocation: Shape): String? {
          return (element.attributes.getAttribute(HTML.Attribute.ALT) as? String)?.nullize(true)
        }

        override fun paint(g: Graphics, allocation: Shape) {
          val g2d = g as Graphics2D
          val savedComposite = g2d.composite
          g2d.composite = AlphaComposite.SrcOver // support transparency
          icon.paintIcon(null, g, allocation.bounds.x, allocation.bounds.y)
          g2d.composite = savedComposite
        }

        @Throws(BadLocationException::class)
        override fun modelToView(pos: Int, a: Shape, b: Bias): Shape {
          val p0 = startOffset
          val p1 = endOffset
          if (pos in p0..p1) {
            val r = a.bounds
            if (pos == p1) {
              r.x += r.width
            }
            r.width = 0
            return r
          }
          throw BadLocationException("$pos not in range $p0,$p1", pos)
        }

        override fun viewToModel(x: Float, y: Float, a: Shape, bias: Array<Bias>): Int {
          val alloc = a as Rectangle
          if (x < alloc.x + alloc.width / 2f) {
            bias[0] = Bias.Forward
            return startOffset
          }
          bias[0] = Bias.Backward
          return endOffset
        }
      }

      companion object {
        private fun preloadedIconsProvider(preloadedIcons: Map<String, Icon>) = { key: String ->
          preloadedIcons[key]
        }
      }
    }

    private class Base64ImagesExtension : Extension {
      override fun invoke(elem: Element, defaultView: View): View? {
        if ("img" != elem.name) return null

        val src = (elem.attributes.getAttribute(HTML.Attribute.SRC) as? String)?.takeIf {
          // example: "data:image/png;base64,ENCODED_IMAGE_HERE"
          it.startsWith("data:image") && it.contains("base64")
        } ?: return null

        val encodedImage = src.split(',').takeIf { it.size == 2 }?.get(1) ?: return null

        try {
          val image = ByteArrayInputStream(Base64.getDecoder().decode(encodedImage)).use(ImageIO::read) ?: return null
          return BufferedImageView(elem, image)
        }
        catch (e: java.lang.IllegalArgumentException) {
          thisLogger().debug(e)
        }
        catch (e: IOException) {
          thisLogger().debug(e)
        }
        return null
      }

      private class BufferedImageView(elem: Element, private val bufferedImage: BufferedImage) : View(elem) {
        private val width: Int
        private val height: Int
        private val border: Int
        private val vAlign: Float

        init {
          val width = getIntAttr(HTML.Attribute.WIDTH, -1)
          val height = getIntAttr(HTML.Attribute.HEIGHT, -1)
          val aspectRatio: Int = bufferedImage.width / bufferedImage.height

          if (width < 0 && height < 0) {
            this.width = bufferedImage.width
            this.height = bufferedImage.height
          }
          else if (width < 0) {
            this.width = height * aspectRatio
            this.height = height
          }
          else if (height < 0) {
            this.width = width
            this.height = width / aspectRatio
          }
          else {
            this.width = width
            this.height = height
          }
          border = getIntAttr(HTML.Attribute.BORDER, DEFAULT_BORDER)
          var alignment = elem.attributes.getAttribute(HTML.Attribute.ALIGN)
          var vAlign = 1.0f
          if (alignment != null) {
            alignment = alignment.toString()
            if ("top" == alignment) {
              vAlign = 0f
            }
            else if ("middle" == alignment) {
              vAlign = .5f
            }
          }
          this.vAlign = vAlign
        }

        private fun getIntAttr(name: HTML.Attribute, defaultValue: Int): Int {
          val attr = element.attributes
          if (!attr.isDefined(name)) return defaultValue

          val value = attr.getAttribute(name) as? String ?: return defaultValue
          return try {
            max(0, value.toInt())
          }
          catch (x: NumberFormatException) {
            defaultValue
          }
        }

        override fun getPreferredSpan(axis: Int): Float {
          return when (axis) {
            X_AXIS -> (width + 2 * border).toFloat()
            Y_AXIS -> (height + 2 * border).toFloat()
            else -> throw IllegalArgumentException("Invalid axis: $axis")
          }
        }

        override fun getToolTipText(x: Float, y: Float, allocation: Shape) =
          super.getElement().attributes.getAttribute(HTML.Attribute.ALT) as? String

        override fun paint(g: Graphics, a: Shape) {
          val bounds = a.bounds
          g.drawImage(bufferedImage, bounds.x + border, bounds.y + border, width, height, null)
        }

        override fun modelToView(pos: Int, a: Shape, b: Bias): Shape? {
          val p0 = startOffset
          val p1 = endOffset
          if (pos in p0..p1) {
            val r = a.bounds
            if (pos == p1) {
              r.x += r.width
            }
            r.width = 0
            return r
          }
          return null
        }

        override fun viewToModel(x: Float, y: Float, a: Shape, bias: Array<Bias>): Int {
          val alloc = a as Rectangle
          if (x < alloc.x + alloc.width) {
            bias[0] = Bias.Forward
            return startOffset
          }
          bias[0] = Bias.Backward
          return endOffset
        }

        override fun getAlignment(axis: Int): Float = if (axis == Y_AXIS) vAlign else super.getAlignment(axis)

        companion object {
          private const val DEFAULT_BORDER = 0
        }
      }
    }
  }

  private class WordWrapExtension : Extension {
    override fun invoke(elem: Element, defaultView: View): View? {
      if (defaultView !is ParagraphView) return null

      return object : ParagraphView(elem) {
        override fun calculateMinorAxisRequirements(axis: Int, requirements: SizeRequirements?): SizeRequirements =
          (requirements ?: SizeRequirements()).apply {
            minimum = layoutPool.getMinimumSpan(axis).toInt()
            preferred = max(minimum, layoutPool.getPreferredSpan(axis).toInt())
            maximum = Int.MAX_VALUE
            alignment = 0.5f
          }
      }
    }
  }

  private class InlineViewExExtension : Extension {
    override fun invoke(element: Element, view: View): View? {
      if (view.javaClass != InlineView::class.java) return null
      val attrs = view.attributes
      if (attrs.getAttribute(CSS.Attribute.PADDING) != null
          || attrs.getAttribute(CSS.Attribute.PADDING_BOTTOM) != null
          || attrs.getAttribute(CSS.Attribute.PADDING_LEFT) != null
          || attrs.getAttribute(CSS.Attribute.PADDING_TOP) != null
          || attrs.getAttribute(CSS.Attribute.PADDING_RIGHT) != null
          || attrs.getAttribute(CSS.Attribute.MARGIN) != null
          || attrs.getAttribute(CSS.Attribute.MARGIN_BOTTOM) != null
          || attrs.getAttribute(CSS.Attribute.MARGIN_LEFT) != null
          || attrs.getAttribute(CSS.Attribute.MARGIN_TOP) != null
          || attrs.getAttribute(CSS.Attribute.MARGIN_RIGHT) != null
          || attrs.getAttribute(CSS_ATTRIBUTE_CAPTION_SIDE)
            ?.asSafely<String>()?.endsWith("px") == true) {
        return InlineViewEx(element)
      }
      return null
    }
  }

  private class BlockViewExExtension : Extension {
    override fun invoke(element: Element, view: View): View? {
      if (view.javaClass != BlockView::class.java) return null
      val attrs = view.attributes
      if (attrs.getAttribute(CSS_ATTRIBUTE_CAPTION_SIDE)
          ?.asSafely<String>()?.endsWith("px") == true) {
        return BlockViewEx(element, (view as BlockView).axis)
      }
      return null
    }
  }

  private class ParagraphViewExExtension : Extension {
    override fun invoke(element: Element, view: View): View? {
      if (view.javaClass != ParagraphView::class.java) return null
      val attrs = view.attributes
      if (attrs.getAttribute(CSS.Attribute.LINE_HEIGHT) != null) {
        return ParagraphViewEx(element)
      }
      return null
    }
  }

  private class FitToWidthImageViewExtension : Extension {
    override fun invoke(element: Element, view: View): View? =
      if (view is ImageView) FitToWidthImageView(element) else null
  }

  private class WbrSupportExtension : Extension {
    override fun invoke(elem: Element, defaultView: View): View? =
      if (elem.name.equals("wbr", true))
        WbrView(elem)
      else
        null
  }
}

private class HiDpiImagesExtension : ExtendableHTMLViewFactory.Extension {
  override fun invoke(elem: Element, defaultView: View): View? {
    if (defaultView !is ImageView) return null
    return HiDpiScalingImageView(elem)
  }
}