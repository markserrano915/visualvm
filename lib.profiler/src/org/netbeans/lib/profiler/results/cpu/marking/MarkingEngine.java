/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Sun Microsystems, Inc. Portions Copyright 1997-2006 Sun
 * Microsystems, Inc. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */

package org.netbeans.lib.profiler.results.cpu.marking;

import org.netbeans.lib.profiler.marker.Mark;
import org.netbeans.lib.profiler.client.ClientUtils;
import org.netbeans.lib.profiler.global.ProfilingSessionStatus;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.netbeans.lib.profiler.marker.Marker;
import org.openide.util.Lookup;


/**
 *
 * @author Jaroslav Bachorik
 */
public class MarkingEngine {
    //~ Inner Interfaces ---------------------------------------------------------------------------------------------------------

    public static interface StateObserver {
        //~ Methods --------------------------------------------------------------------------------------------------------------

        void stateChanged(MarkingEngine instance);
    }

    //~ Static fields/initializers -----------------------------------------------------------------------------------------------

    private static MarkingEngine instance;

    //~ Instance fields ----------------------------------------------------------------------------------------------------------

    private final Object mapperGuard = new Object();
    private final Object markGuard = new Object();

    // @GuardedBy mapperGuard
    private MarkMapper mapper = null;

    // @GuardedBy markGuard
    private String[] labels;

    // @GuardedBy markGuard  
    private Mark[] markBackMap;

    // @GuardedBy markGuard
    private MarkMapping[] marks;

    private Lookup.Result observers;
    
    //~ Constructors -------------------------------------------------------------------------------------------------------------

    /**
     * Creates a new instance of MarkingEngine
     */
    private MarkingEngine() {
        observers = Lookup.getDefault().lookupResult(StateObserver.class);

        //    synchronized(filterGuard) {
        //      filter = new MarkFilter();
        //      CPUStatsCollector.getDefault().addListener(filter);
        //    }
        synchronized (mapperGuard) {
            mapper = new MarkMapper();
        }
    }

    //~ Methods ------------------------------------------------------------------------------------------------------------------

    public static synchronized MarkingEngine getDefault() {
        if (instance == null) {
            instance = new MarkingEngine();
        }

        return instance;
    }

    // configure the engine from a given {@linkplain Lookup}
    public static synchronized void configure(Lookup lookup) {
        Marker marker = (Marker)lookup.lookup(Marker.class);
        getDefault().setMarks(marker != null ? marker.getMappings() : Marker.DEFAULT.getMappings());
    }
    
    public static synchronized void deconfigure() {
        getDefault().setMarks(Marker.DEFAULT.getMappings());
    }

//    public String getLabelForId(char markId) {
//        synchronized (markGuard) {
//            if (marks == null) {
//                return null;
//            }
//
//            if (((int) markId > 0) && ((int) markId <= labels.length)) {
//                return labels[(int) markId - 1];
//            } else {
//                return null;
//            }
//        }
//    }

//    public Mark getMarkForId(char markId) {
//        synchronized (markGuard) {
//            if (marks == null) {
//                return null;
//            }
//
//            if (((int) markId > 0) && ((int) markId <= labels.length)) {
//                return markBackMap[(int) markId - 1];
//            } else {
//                return (defaultMark != null) ? defaultMark : Mark.DEFAULT;
//            }
//        }
//    }

    public ClientUtils.SourceCodeSelection[] getMarkerMethods() {
        synchronized (markGuard) {
            if (marks == null) {
                return new ClientUtils.SourceCodeSelection[0];
            }

            ClientUtils.SourceCodeSelection[] methods = new ClientUtils.SourceCodeSelection[marks.length];

            for (int i = 0; i < marks.length; i++) {
                methods[i] = marks[i].markMask;
            }

            return methods;
        }
    }

    public int getNMarks() {
        synchronized (markGuard) {
            return (labels != null) ? labels.length : 0;
        }
    }

    public Mark markMethod(int methodId, ProfilingSessionStatus status) {
        synchronized(mapper) {
            return mapper.getMark(methodId, status);
        }
    }
    
    Mark mark(int methodId, ProfilingSessionStatus status) {
        ClientUtils.SourceCodeSelection method = null;

        synchronized (markGuard) {
            if (marks == null) {
                return Mark.DEFAULT;
            }

            status.beginTrans(false);

            try {
                method = new ClientUtils.SourceCodeSelection(status.getInstrMethodClasses()[methodId],
                                                             status.getInstrMethodNames()[methodId],
                                                             status.getInstrMethodSignatures()[methodId]);
            } finally {
                status.endTrans();
            }

            String methodSig = method.toFlattened();

            for (int i = 0; i < marks.length; i++) {
                if (methodSig.startsWith(marks[i].markSig)) {
                    return marks[i].mark;

                    //          int supposedMark = getMarkId(marks[i].mark);
                    //          return (char)(supposedMark >= 0 ? supposedMark : 0);
                }
            }

            return Mark.DEFAULT;
        }
    }

    private void setMarks(MarkMapping[] marks) {
        boolean stateChange = false;

        synchronized (markGuard) {
            stateChange = !((this.marks == null) && (marks == null))
                          && (((this.marks == null) && (marks != null)) || ((this.marks != null) && (marks == null))
                             || !this.marks.equals(marks));
            this.marks = marks;

//            if (marks != null) {
//                Set labelSet = new LinkedHashSet();
//
//                for (int i = 0; i < marks.length; i++) {
//                    // add labels
//                    labelSet.addAll(marks[i].mark.getLabels());
//                    // update default mark
//                    if (marks[i].mark.isDefault) {
//                        defaultMark = marks[i].mark;
//                    }
//                }
//
//                labels = new String[labelSet.size()];
//                labels = (String[]) labelSet.toArray(labels);
//
//                markBackMap = new Mark[labels.length];
//
//                for (int i = 0; i < labels.length; i++) {
//                    for (int j = 0; j < marks.length; j++) {
//                        if (marks[j].mark.getId().equals(labels[i])) {
//                            markBackMap[i] = marks[j].mark;
//
//                            break;
//                        }
//                    }
//                }
//            }
        }
        if (stateChange) {
            fireStateChanged();
        }
    }

    private void fireStateChanged() {
        for (Iterator iter = observers.allInstances().iterator(); iter.hasNext();) {
            ((StateObserver) iter.next()).stateChanged(this);
        }
    }
}
