/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import com.google.common.base.Preconditions;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.basetree.AbstractNode;


/**
 * Abstract implementation of a SoyNode.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * @author Kai Huang
 */
public abstract class AbstractSoyNode extends AbstractNode implements SoyNode {


  /** The id for this node. */
  private int id;

  /** The first line in the file from which this node was parsed or derived. */
  private SourceLocation srcLoc;


  /**
   * @param id The id for this node.
   */
  protected AbstractSoyNode(int id) {
    this.id = id;
    srcLoc = SourceLocation.UNKNOWN;
  }


  /**
   * Copy constructor.
   * @param orig The node to copy.
   */
  protected AbstractSoyNode(AbstractSoyNode orig) {
    super(orig);
    this.id = orig.id;
    this.srcLoc = orig.srcLoc;
  }


  @Override public void setId(int id) {
    this.id = id;
  }


  @Override public int getId() {
    return id;
  }


  /** The first line of the node in the file from which it was parsed or derived. */
  @Override public SourceLocation getSourceLocation() {
    return srcLoc;
  }


  @Override public void setSourceLocation(SourceLocation srcLoc) {
    Preconditions.checkNotNull(srcLoc);
    this.srcLoc = srcLoc;
  }


  @Override public ParentSoyNode<?> getParent() {
    return (ParentSoyNode<?>) super.getParent();
  }


  @Override public abstract SoyNode clone();


  @Override public String toString() {
    return super.toString() + "_" + id;
  }

}
