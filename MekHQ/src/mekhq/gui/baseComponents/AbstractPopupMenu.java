/*
 * Copyright (c) 2021 - The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MekHQ.
 *
 * MekHQ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MekHQ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MekHQ. If not, see <http://www.gnu.org/licenses/>.
 */
package mekhq.gui.baseComponents;

import megamek.client.ui.swing.util.MenuScroller;
import megamek.common.util.EncodeControl;
import mekhq.MekHqConstants;

import javax.swing.*;
import java.util.ResourceBundle;

public abstract class AbstractPopupMenu extends JPopupMenu {
    //region Variable Declarations
    protected final ResourceBundle resources = ResourceBundle.getBundle("mekhq.resources.GUI", new EncodeControl());
    //endregion Variable Declarations

    //region Constructors
    public AbstractPopupMenu(final String name) {
        super();
        setName(name);
    }

    public AbstractPopupMenu(final String name, final String text) {
        super(text);
        setName(name);
    }
    //endregion Constructors

    /**
     * This is used to add a JMenu to this, provided the former isn't empty, and then add a scroller
     * to the child if it is above the default minimum threshold
     * @param child the JMenu to add
     */
    public void add(final JMenu child) {
        add(child, MekHqConstants.BASE_SCROLLER_THRESHOLD);
    }

    /**
     * This is used to add a JMenu this, provided the former isn't empty, and then add a scroller
     * to the child if it is above the provided threshold
     * @param child the JMenu to add
     * @param scrollerThreshold the threshold for adding a scroller
     */
    public void add(final JMenu child, final int scrollerThreshold) {
        if (child.getItemCount() > 0) {
            super.add(child);
            if (child.getItemCount() > scrollerThreshold) {
                MenuScroller.setScrollerFor(child, scrollerThreshold);
            }
        }
    }
}
