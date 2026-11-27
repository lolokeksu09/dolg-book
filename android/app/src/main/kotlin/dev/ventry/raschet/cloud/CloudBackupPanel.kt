package dev.ventry.raschet.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ventry.raschet.data.ClientCommand.FileTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.function.Consumer

private data class Choice(val slot:Int,val current:CloudSlot?,val restore:Boolean)
@Composable
internal fun CloudBackupPanel(submit:Consumer<FileTask>,preferencesName:String){
    val app=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var slots by remember{mutableStateOf<List<CloudSlot>?>(null)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var choice by remember{mutableStateOf<Choice?>(null)}
    fun refresh(){
        if(loading)return
        loading=true;error=""
        scope.launch{
            try{slots=withContext(Dispatchers.IO){CloudBackupClient.list(app)}}
            catch(e:CancellationException){throw e}
            catch(e:IllegalArgumentException){slots=null;error=e.message?:"Не удалось открыть облако."}
            catch(_:Exception){slots=null;error="Не удалось подключиться к облаку. Локальная книга доступна."}
            finally{loading=false}
        }
    }
    LaunchedEffect(Unit){refresh()}
    Card(Modifier.fillMaxWidth().testTag("cloud_panel")){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Облачное хранилище",style=MaterialTheme.typography.titleLarge)
            Text("3 слота · до 16 МиБ на копию",style=MaterialTheme.typography.bodyMedium)
            Text("Книга шифруется на телефоне. Пароль не хранится на сервере. Запишите его отдельно: Ventry не сможет восстановить забытый пароль.",style=MaterialTheme.typography.bodySmall)
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
            OutlinedButton(onClick={refresh()},enabled=!loading,modifier=Modifier.fillMaxWidth().testTag("cloud_refresh")){Text("Обновить список")}
            slots?.let{items->
                for(i in 1..3){
                    val item=items.firstOrNull{it.slot==i}
                    HorizontalDivider()
                    Text("Слот $i",style=MaterialTheme.typography.titleMedium)
                    Text(if(item==null)"Свободен" else
                        item.savedAt.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",Locale("ru")))+
                        " · "+String.format(Locale("ru"),"%.1f МиБ",item.size/1048576.0),
                        style=MaterialTheme.typography.bodyMedium)
                    Button(onClick={choice=Choice(i,item,false)},enabled=!loading,modifier=Modifier.fillMaxWidth().testTag("cloud_put_$i")){
                        Text(if(item==null)"Сохранить копию" else "Заменить копию")
                    }
                    if(item!=null)OutlinedButton(onClick={choice=Choice(i,item,true)},enabled=!loading,modifier=Modifier.fillMaxWidth().testTag("cloud_restore_$i")){Text("Восстановить книгу")}
                }
            }
            Text("Локальная автозащита и экспорт JSON работают отдельно. Облачные копии создаются вручную; при окончании подписки скачивание с текущей привязки сохраняется.",style=MaterialTheme.typography.bodySmall)
        }
    }
    choice?.let{selected->
        var password by remember(selected){mutableStateOf("")}
        var repeat by remember(selected){mutableStateOf("")}
        val valid= password.length in 12..128 && password.any{!it.isWhitespace()} && (selected.restore||password==repeat)
        AlertDialog(onDismissRequest={password="";repeat="";choice=null},
            properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn,dismissOnClickOutside=false),
            title={Text(if(selected.restore)"Восстановить слот "+selected.slot else if(selected.current!=null)"Заменить слот "+selected.slot else "Сохранить слот "+selected.slot)},
            text={Column(Modifier.heightIn(max=460.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(if(selected.restore)"Текущая книга будет заменена после проверки пароля и копии. Сначала будет создана защищённая локальная точка." else
                    if(selected.current!=null)"Предыдущая копия в этом слоте будет заменена. Остальные слоты сохранятся." else "Создайте пароль восстановления и сохраните его отдельно.")
                OutlinedTextField(password,{password=it.take(128)},label={Text("Пароль копии")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                    modifier=Modifier.fillMaxWidth().testTag("cloud_password"))
                if(!selected.restore)OutlinedTextField(repeat,{repeat=it.take(128)},label={Text("Повторите пароль")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                Text("Не менее 12 символов. Регистр и пробелы учитываются.",style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(enabled=valid,onClick={
                val secret=password.toCharArray()
                password="";repeat="";choice=null
                val command=if(selected.restore){
                    val item=checkNotNull(selected.current)
                    FileTask.Cloud.Restore(item.slot,item.version,item.size,item.sha256,preferencesName,secret)
                }else FileTask.Cloud.Put(selected.slot,selected.current?.version,secret)
                submit.accept(command)
            },modifier=Modifier.testTag("cloud_confirm")){Text(if(selected.restore)"Восстановить" else "Сохранить")}},
            dismissButton={TextButton(onClick={password="";repeat="";choice=null}){Text("Отмена")}})
    }
}
