

export function humanizeTime(time: string,translate:any){
    //TODO:用ngx-translate翻译
    let time_ = time.split(/[\s-:]+/).map(Number);
    let date = new Date(time_[0], time_[1]-1, time_[2], time_[3], time_[4], time_[5]);
    let now = new Date;
    let seconds = (now.getTime() - date.getTime()) / 1000;
    if (seconds <= 0) {
        return 0 + translate.instant("second ago")
    }
    var numyears = Math.floor(seconds / 31536000);
    if (numyears !== 0) {
        return numyears===1 ? numyears + translate.instant("year ago") : numyears + translate.instant("year ago");
    }
    var numdays = Math.floor((seconds % 31536000) / 86400);
    if (numdays !== 0) {
        return numdays===1 ? numdays + translate.instant("day ago") : numdays + translate.instant("days ago");
    }
    var numhours = Math.floor(((seconds % 31536000) % 86400) / 3600);
    if (numhours !== 0) {
        return numhours===1 ? numhours + translate.instant("hour ago") : numhours + translate.instant("hours ago");
    }
    var numminutes = Math.floor((((seconds % 31536000) % 86400) % 3600) / 60);
    if (numminutes !== 0) {
        return numminutes===1 ? numminutes + translate.instant("minute ago") : numminutes + translate.instant("minutes ago");
    }
    var numseconds = (((seconds % 31536000) % 86400) % 3600) % 60;
    return numseconds===1 ? numseconds + translate.instant("second ago") : numseconds + translate.instant("seconds ago");

}






