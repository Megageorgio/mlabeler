package mlabeler.app

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatDateTime(epochMs: Long): String {
    val f = NSDateFormatter()
    f.dateFormat = "yyyy-MM-dd HH:mm"
    return f.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}
