/*******************************************************************************
 * Copyright (c) 2001-2004 quickfixengine.org All rights reserved.
 * 
 * This file is part of the QuickFIX FIX Engine
 * 
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file LICENSE
 * included in the packaging of this file.
 * 
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING THE
 * WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.
 * 
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 * 
 * Contact ask@quickfixengine.org if any conditions of this licensing are not
 * clear to you.
 *  
 ******************************************************************************/

package quickfix;

public class SystemTime {
    private static SystemTimeSource systemTimeSource = new SystemTimeSource() {
        public long getTime() {
            return System.currentTimeMillis();
        }
    };
    
    public static long get() {
        return systemTimeSource.getTime();
    }
    
    public static void setTimeSource(SystemTimeSource systemTimeSource) {
        SystemTime.systemTimeSource = systemTimeSource;
    }
}
