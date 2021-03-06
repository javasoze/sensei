package com.senseidb.search.relevance.impl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Set;

import com.browseengine.bobo.facets.data.MultiValueFacetDataCache;
import com.browseengine.bobo.facets.data.TermLongList;

public class MFacetLong extends MFacet {

  public MFacetLong(MultiValueFacetDataCache<?> mDataCaches) {
    super(mDataCaches);
  }

  @Override
  public boolean containsAll(Set<?> set) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  public boolean containsAll(long[] target) {
    throw new UnsupportedOperationException("not implemented yet");
  }

  @Override
  public boolean containsAny(Object set) {
    LongOpenHashSet setLong = (LongOpenHashSet) set;
    for (int i = 0; i < this._length; i++)
      if (setLong.contains(((TermLongList) _mTermList).getPrimitiveValue(_buf[i]))) return true;

    return false;
  }

  public boolean contains(long target) {
    for (int i = 0; i < this._length; i++)
      if (((TermLongList) _mTermList).getPrimitiveValue(_buf[i]) == target) return true;

    return false;
  }

}
