package dev.ventry.raschet;

import dev.ventry.raschet.data.CatalogRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Search index built once per purchase, not once per row or keystroke. */
final class CatalogPicker {
    static final int LIMIT=24;
    private static final class Indexed {
        final CatalogRecord product;
        final String name,category;
        Indexed(CatalogRecord product){
            this.product=product;
            name=Product.normalize(product.getName());
            category=Product.normalize(product.getCategory());
        }
        int rank(String query,String[] words){
            for(String word:words)if(!name.contains(word)&&!category.contains(word))return -1;
            if(query.isEmpty())return 0;
            if(name.equals(query))return 0;
            if(name.startsWith(query))return 1;
            if(name.contains(query))return 2;
            return 3;
        }
    }
    static final class Result {
        final List<CatalogRecord> rows;
        final int total;
        Result(List<CatalogRecord> rows,int total){this.rows=rows;this.total=total;}
    }
    private static final class Match {
        final Indexed row;final int rank;
        Match(Indexed row,int rank){this.row=row;this.rank=rank;}
    }
    private final List<Indexed> index=new ArrayList<>();
    CatalogPicker(List<CatalogRecord> source){
        for(CatalogRecord product:source)if(!product.getArchived())index.add(new Indexed(product));
    }
    boolean isEmpty(){return index.isEmpty();}
    Result search(String query,boolean favorites){
        String normalized=Product.normalize(query);
        String[] words=normalized.isEmpty()?new String[0]:normalized.split(" ");
        List<Match> matches=new ArrayList<>();
        for(Indexed row:index){
            if(favorites&&!row.product.getFavorite())continue;
            int rank=row.rank(normalized,words);
            if(rank>=0)matches.add(new Match(row,rank));
        }
        matches.sort(Comparator.comparingInt((Match m)->m.rank)
                .thenComparing(m->!m.row.product.getFavorite())
                .thenComparing(m->m.row.name).thenComparing(m->m.row.product.getId()));
        List<CatalogRecord> shown=new ArrayList<>();
        for(int i=0;i<Math.min(LIMIT,matches.size());i++)shown.add(matches.get(i).row.product);
        return new Result(shown,matches.size());
    }
}
