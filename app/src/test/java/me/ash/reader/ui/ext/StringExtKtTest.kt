package me.ash.reader.ui.ext

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class StringExtTest {

    @Test
    fun testExtractDomain() {
        Assert.assertEquals(null, "".extractDomain())
        Assert.assertEquals(null, null.extractDomain())
        var case = "https://ash7.io"
        Assert.assertEquals("ash7.io", case.extractDomain())
        case = "ash7.io"
        Assert.assertEquals("ash7.io", case.extractDomain())
        case = "https://ash7.io/blog/hello/"
        Assert.assertEquals("ash7.io", case.extractDomain())
        case = "http://ash7.io/blog/hello/"
        Assert.assertEquals("ash7.io", case.extractDomain())
        case = "file://ash7.io/blog"
        Assert.assertEquals("ash7.io", case.extractDomain())
        case = "file://127.0.0.1/blog"
        Assert.assertEquals("127.0.0.1", case.extractDomain())
        case = "ftp://127.0.0.1"
        Assert.assertEquals("127.0.0.1", case.extractDomain())
    }

    @Test
    fun testOriginReferer() {
        Assert.assertEquals(null, null.originReferer())
        Assert.assertEquals(null, "".originReferer())
        Assert.assertEquals(null, "ash7.io".originReferer())
        Assert.assertEquals(null, "file:///android_res/font/a.ttf".originReferer())
        Assert.assertEquals(null, "data:image/png;base64,AAAA".originReferer())
        Assert.assertEquals(
            "https://i.pximg.net/",
            "https://i.pximg.net/img-original/img/1_p0.jpg?x=1#y".originReferer(),
        )
        Assert.assertEquals("http://ash7.io/", "HTTP://ash7.io".originReferer())
        Assert.assertEquals("http://10.0.2.2:8765/", "http://10.0.2.2:8765/a.jpg".originReferer())
    }
}
