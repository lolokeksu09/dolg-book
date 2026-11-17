import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';

// Only a previous output carrying our marker may be replaced.
const out='amvera-dist', marker=join(out,'.dolg-build-marker');
if(existsSync(out)){
 if(!existsSync(marker)||readFileSync(marker,'utf8')!=='dolg-book-amvera-output-v1\n')throw new Error('Refusing to overwrite an unrecognised directory');
 rmSync(out,{recursive:true});
}
mkdirSync(join(out,'server'),{recursive:true});mkdirSync(join(out,'dist'),{recursive:true});
writeFileSync(marker,'dolg-book-amvera-output-v1\n');
for(const name of ['auth.js','http.js','start.js','store.js'])copyFileSync(join('server',name),join(out,'server',name));
for(const name of ['app.js','ledger.js','style.css'])copyFileSync(join('dist',name),join(out,'dist',name));
copyFileSync('package.json',join(out,'package.json'));
const sourceLink='<a href="project-source.zip" download>Скачать исходники и план запуска (ZIP)</a>';
const page=readFileSync('dist/index.html','utf8');
if(page.split(sourceLink).length!==2)throw new Error('Expected source download link was not found exactly once');
writeFileSync(join(out,'dist/index.html'),page.replace(sourceLink,'<a href="https://github.com/lolokeksu09/dolg-book" target="_blank" rel="noopener">Исходный код на GitHub</a>'));
console.log('Prepared Amvera runtime files');
