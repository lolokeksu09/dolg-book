package dev.ventry.raschet;

import java.io.IOException;
import java.io.Reader;

/** Bounds individual JSON tokens and row objects before JsonReader can allocate them. */
final class ImportJsonGuard extends Reader {
    private static final int MAX_TOKEN=8192, MAX_ROW=65536, MAX_DEPTH=32;
    private final Reader source;
    private int depth,token,row;
    private boolean quoted,escaped,inRow;
    ImportJsonGuard(Reader source){this.source=source;}
    @Override public int read(char[] buffer,int offset,int length) throws IOException{
        int n=source.read(buffer,offset,length);
        for(int i=0;i<n;i++)inspect(buffer[offset+i]);
        return n;
    }
    private void inspect(char ch) throws IOException{
        if(inRow&&++row>MAX_ROW)throw new IOException("Строка книги слишком большая.");
        if(quoted){
            if(++token>MAX_TOKEN)throw new IOException("Поле JSON слишком большое.");
            if(escaped){escaped=false;return;}
            if(ch=='\\'){escaped=true;return;}
            if(ch=='"'){quoted=false;token=0;}
            return;
        }
        if(ch=='"'){quoted=true;token=0;return;}
        if(ch=='{'||ch=='['){
            token=0;
            if(++depth>MAX_DEPTH)throw new IOException("Слишком глубокая структура JSON.");
            if(depth==3){inRow=true;row=1;}
            return;
        }
        if(ch=='}'||ch==']'){
            token=0;
            if(depth==3){inRow=false;row=0;}
            depth--;return;
        }
        if(ch==','||ch==':'||Character.isWhitespace(ch)){token=0;return;}
        if(++token>MAX_TOKEN)throw new IOException("Поле JSON слишком большое.");
    }
    @Override public void close() throws IOException{source.close();}
}
